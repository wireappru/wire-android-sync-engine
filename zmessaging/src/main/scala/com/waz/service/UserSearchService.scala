/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.content.{MembersStorage, MessagesStorage, SearchQueryCacheStorage, UsersStorage}
import com.waz.model.SearchQuery.{Recommended, RecommendedHandle}
import com.waz.model.UserData.{ConnectionStatus, UserDataDao}
import com.waz.model.{SearchQuery, _}
import com.waz.service.ContactResult.ContactMethod
import com.waz.service.conversation.{ConversationsService, ConversationsUiService}
import com.waz.service.invitations.InvitationService
import com.waz.service.teams.TeamsService
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.UserSearchClient.UserSearchEntry
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils._
import com.waz.utils.events._
import org.threeten.bp.Instant

import scala.collection.immutable.Set
import scala.collection.{breakOut, mutable}
import scala.concurrent.Future
import scala.concurrent.duration._

case class SearchResults(top:   IndexedSeq[UserData]         = IndexedSeq.empty,
                         local: IndexedSeq[UserData]         = IndexedSeq.empty,
                         convs: IndexedSeq[ConversationData] = IndexedSeq.empty,
                         dir:   IndexedSeq[UserData]         = IndexedSeq.empty, //directory (backend search)
                         ab:    IndexedSeq[ContactResult]    = IndexedSeq.empty) { //addressBook
  override def toString = s"SearchResults(top: ${top.size}, local: ${local.size}, convs: ${convs.size}, dir: ${dir.size}, ab: ${ab.size})"
}
case class ContactResult(contact: Contact, invited: Boolean) {
  def contactMethods =
    contact.emailAddresses.map(e => ContactMethod(contact, Left(e)))
      .++(contact.phoneNumbers.map(p => ContactMethod(contact, Right(p)))).toSeq
}

object ContactResult {
  case class ContactMethod(contact: Contact, method: Either[EmailAddress, PhoneNumber]) {
    import ContactMethod._
    def stringRepresentation: String = method.fold(_.str, _.str)
    def getType: ContactType = method.fold(_ => Email, _ => Phone)
  }

  object ContactMethod {
    trait ContactType
    object Email extends ContactType
    object Phone extends ContactType
  }
}

class UserSearchService(selfUserId:           UserId,
                        queryCache:           SearchQueryCacheStorage,
                        teamId:               Option[TeamId],
                        userService:          UserService,
                        usersStorage:         UsersStorage,
                        teamsService:         TeamsService,
                        membersStorage:       MembersStorage,
                        timeouts:             Timeouts,
                        sync:                 SyncServiceHandle,
                        messages:             MessagesStorage,
                        convsUi:              ConversationsUiService,
                        conversationsService: ConversationsService,
                        invitations:          InvitationService,
                        contacts:             ContactsService
                       ) {

  import Threading.Implicits.Background
  import com.waz.service.UserSearchService._
  import timeouts.search._

  ClockSignal(1.day)(i => queryCache.deleteBefore(i - cacheExpiryTime))(EventContext.Global)

  private val exactMatchUser = new SourceSignal[Option[UserData]]()
  private val signalMap = mutable.HashMap[SearchQuery, Signal[IndexedSeq[UserData]]]()

  def searchLocal(filter: Filter = "", toConv: Option[ConvId] = None, showBlockedUsers: Boolean = false): Signal[IndexedSeq[UserData]] = {
    val isHandle = Handle.isHandle(filter)
    val symbolStripped = if (isHandle) Handle.stripSymbol(filter) else filter
    for {
      connected <- userService.acceptedOrBlockedUsers.map(_.values)
      members <- teamId.fold(Signal.const(Set.empty[UserData])) { _ =>
        teamsService.searchTeamMembers(if (filter.isEmpty) None else Some(SearchKey(filter)), handleOnly = Handle.isHandle(filter))
      }
      exc <- toConv.fold(Signal.const(Set.empty[UserId]))(membersStorage.activeMembers).map(_ + selfUserId)
    } yield {
      val users =
        if (filter.nonEmpty) connected.filter(
          connectedUsersPredicate(
            searchTerm         = filter,
            filteredIds        = exc.map(_.str),
            alsoSearchByEmail  = true,
            showBlockedUsers   = showBlockedUsers,
            searchByHandleOnly = isHandle))
        else connected

      val includedIds = (users.map(_.id).toSet ++ members.map(_.id)).diff(exc)
      sortUsers((connected ++ members).filter(u => includedIds.contains(u.id)).toIndexedSeq.filter(!_.isWireBot), filter, isHandle, symbolStripped)
    }
  }

  private def sortUsers(results: IndexedSeq[UserData], filter: Filter, isHandle: Boolean, symbolStripped: Filter): IndexedSeq[UserData] = {
    def toLower(str: String) = Locales.transliteration.transliterate(str).trim.toLowerCase

    val predicate: (UserData) => Int =
      if (filter.isEmpty) (_: UserData) => 0
      else if (isHandle) (u: UserData) => if (u.handle.exists(_.exactMatchQuery(filter))) 0 else 1
      else (u: UserData) => {
        val userName = toLower(u.getDisplayName)
        val query = toLower(symbolStripped)
        if (userName == query) 0 else if (userName.startsWith(query)) 1 else 2
      }

    results.sortBy(predicate)
  }

  def search(filter: Filter = "", selectedUsers: Set[UserId] = Set.empty, toConv: Option[ConvId] = None): Signal[SearchResults] = {

    val isHandle       = Handle.isHandle(filter)
    val symbolStripped = if (isHandle) Handle.stripSymbol(filter) else filter
    val query          = if (isHandle) RecommendedHandle(filter) else Recommended(filter)

    val shouldShowTopUsers   = filter.isEmpty && teamId.isEmpty && toConv.isEmpty
    val shouldShowAbContacts = toConv.isEmpty && selectedUsers.isEmpty && teamId.isEmpty

    val shouldShowGroupConversations = (if (isHandle) symbolStripped.length > 1 else !filter.isEmpty) && selectedUsers.isEmpty && toConv.isEmpty
    val shouldShowDirectorySearch    = !filter.isEmpty && selectedUsers.isEmpty && toConv.isEmpty

    exactMatchUser ! None // reset the exact match to None on any query change

    if (filter.isEmpty) Future.successful {
      System.gc() // TODO: [AN-5497] the user search should not create so many objects to trigger GC in-between
    }

    val excluded = toConv.fold(Signal.const(Set.empty[UserId]))(membersStorage.activeMembers(_).map(_.toSet)).map(_ + selfUserId)

    val topUsers: Signal[IndexedSeq[UserData]] =
      if (shouldShowTopUsers)
        for {
          top <- topPeople
          exc <- excluded
        } yield top.filter(u => !exc.contains(u.id) && !u.isWireBot)
      else Signal.const(IndexedSeq.empty)

    val conversations: Signal[IndexedSeq[ConversationData]] =
      if (shouldShowGroupConversations)
        Signal.future(convsUi.findGroupConversations(SearchKey(filter), Int.MaxValue, handleOnly = isHandle))
          .map(_.filter(conv => teamId.forall(conv.team.contains)).distinct.toIndexedSeq)
          .flatMap { convs =>
            val gConvs = convs.map { c =>
              conversationsService.isGroupConversation(c.id).flatMap {
                case true  => Future.successful(true)
                case false => conversationsService.isWithBot(c.id)
              }.map {
                case true  => Some(c)
                case false => None
              }
            }
            Signal.future(Future.sequence(gConvs).map(_.flatten)) //TODO avoid using Signal.future - will not update...
          }
      else Signal.const(IndexedSeq.empty)

    val directorySearch: Signal[IndexedSeq[UserData]] =
      for {
        dir <-
          if (shouldShowDirectorySearch)
            (for {
              res <- searchUserData(query)
              exc <- excluded
            } yield res.filter(u => !exc.contains(u.id) && !u.isWireBot)).map(us => sortUsers(us, filter, isHandle, symbolStripped))
          else Signal.const(IndexedSeq.empty)
        exact <- exactMatchUser
      } yield {
        (dir, exact) match {
          case (_, None) => dir
          case (IndexedSeq(), Some(ex)) => IndexedSeq(ex)
          case (results, Some(ex)) => (results.toSet ++ Set(ex)).toIndexedSeq
        }
      }

    val abContacts: Signal[IndexedSeq[ContactResult]] =
      if (shouldShowAbContacts)
        for {
          invited <- invitations.invitedContacts
          ab      <- contacts.unifiedContacts
          onWire  <- contacts.contactsOnWire
        } yield ab.contacts.values.toIndexedSeq
          .map(c => ContactResult(c, invited = invited.contains(c.id)))
          .filterNot(c => onWire.containsRight(c.contact.id))
          .filter(c => filter.isEmpty || c.contact.name.toLowerCase.contains(filter.toLowerCase)) //TODO: proper filter
      else Signal.const(IndexedSeq.empty)

    for {
      top   <- topUsers
      local <- searchLocal(filter, toConv, showBlockedUsers = true)
      convs <- conversations
      dir   <- directorySearch
      ab    <- abContacts
    } yield SearchResults(top, local, convs, dir, ab)
  }

  def updateSearchResults(query: SearchQuery, results: Seq[UserSearchEntry]) = {
    def updating(ids: Vector[UserId])(cached: SearchQueryCache) = cached.copy(query, Instant.now, if (ids.nonEmpty || cached.entries.isEmpty) Some(ids) else cached.entries)

    for {
      updated <- userService.updateUsers(results)
      _ <- userService.syncIfNeeded(updated.toSeq: _*)
      ids = results.map(_.id)(breakOut): Vector[UserId]
      _ = verbose(s"updateSearchResults($query, ${results.map(_.handle)})")
      _ <- queryCache.updateOrCreate(query, updating(ids), SearchQueryCache(query, Instant.now, Some(ids)))
    } yield ()

    query match {
      case RecommendedHandle(handle) if !results.map(_.handle).exists(_.exactMatchQuery(handle)) =>
        debug(s"exact match requested: $handle")
        sync.exactMatchHandle(Handle(Handle.stripSymbol(handle)))
      case _ =>
    }

    Future.successful({})
  }

  def updateExactMatch(handle: Handle, userId: UserId) = {
    val query = RecommendedHandle(handle.withSymbol)
    def updating(id: UserId)(cached: SearchQueryCache) = cached.copy(query, Instant.now, Some(cached.entries.map(_.toSet ++ Set(userId)).getOrElse(Set(userId)).toVector))

    debug(s"update exact match: $handle, $userId")
    userService.getUser(userId).collect {
      case Some(user) =>
        debug(s"received exact match: ${user.handle}")
        exactMatchUser ! Some(user)
        queryCache.updateOrCreate(query, updating(userId), SearchQueryCache(query, Instant.now, Some(Vector(userId))))
    }(Threading.Background)

    Future.successful({})
  }

  def searchUserData(query: SearchQuery): Signal[IndexedSeq[UserData]] = signalMap.getOrElseUpdate(query, returning( startNewSearch(query) ) { _ =>
    CancellableFuture.delay(cacheExpiryTime).map { _ =>
      signalMap.remove(query)
      queryCache.remove(query)
    }
  })

  private def startNewSearch(query: SearchQuery): Signal[IndexedSeq[UserData]] = returning( queryCache.optSignal(query) ){ _ =>
    localSearch(query).flatMap(_ => sync.syncSearchQuery(query))
  }.flatMap {
    case None => Signal.const(IndexedSeq.empty[UserData])
    case Some(cached) => cached.entries match {
      case None => Signal.const(IndexedSeq.empty[UserData])
      case Some(ids) if ids.isEmpty => Signal.const(IndexedSeq.empty[UserData])
      case Some(ids) => usersStorage.listSignal(ids).map(_.toIndexedSeq)
    }
  }

  private def localSearch(query: SearchQuery) = (query match {
    case Recommended(prefix) =>
      usersStorage.find[UserData, Vector[UserData]](recommendedPredicate(prefix), db => UserDataDao.recommendedPeople(prefix)(db), identity)
    case RecommendedHandle(prefix) =>
      usersStorage.find[UserData, Vector[UserData]](recommendedHandlePredicate(prefix), db => UserDataDao.recommendedPeople(prefix)(db), identity)
    case _ => Future.successful(Vector.empty[UserData])
  }).flatMap { users =>
    lazy val fresh = SearchQueryCache(query, Instant.now, Some(users.map(_.id)))

    def update(q: SearchQueryCache): SearchQueryCache = if ((cacheExpiryTime elapsedSince q.timestamp) || q.entries.isEmpty) fresh else q

    queryCache.updateOrCreate(query, update, fresh)
  }

  private def topPeople = {
    def messageCount(u: UserData) = messages.countLaterThan(ConvId(u.id.str), Instant.now - topPeopleMessageInterval)

    val loadTopUsers = (for {
      conns         <- usersStorage.find[UserData, Vector[UserData]](topPeoplePredicate, db => UserDataDao.topPeople(db), identity)
      messageCounts <- Future.sequence(conns.map(messageCount))
    } yield conns.zip(messageCounts)).map { counts =>
      counts.filter(_._2 > 0).sortBy(_._2)(Ordering[Long].reverse).take(MaxTopPeople).map(_._1)
    }

    Signal.future(loadTopUsers).map(_.toIndexedSeq)
  }

  private val topPeoplePredicate: UserData => Boolean = u => ! u.deleted && u.connection == ConnectionStatus.Accepted

  private def recommendedPredicate(prefix: String): UserData => Boolean = {
    val key = SearchKey(prefix)
    u => ! u.deleted && ! u.isConnected && (key.isAtTheStartOfAnyWordIn(u.searchKey) || u.email.exists(_.str == prefix) || u.handle.exists(_.startsWithQuery(prefix)))
  }

  private def recommendedHandlePredicate(prefix: String): UserData => Boolean = {
    u => ! u.deleted && ! u.isConnected && u.handle.exists(_.startsWithQuery(prefix))
  }

  private def connectedUsersPredicate(searchTerm: String,
                                      filteredIds: Set[String],
                                      alsoSearchByEmail: Boolean,
                                      showBlockedUsers: Boolean,
                                      searchByHandleOnly: Boolean): UserData => Boolean = {
    val query = SearchKey(searchTerm)
    user =>
      ((query.isAtTheStartOfAnyWordIn(user.searchKey) && !searchByHandleOnly) ||
        user.handle.exists(_.startsWithQuery(searchTerm)) ||
        (alsoSearchByEmail && user.email.exists(e => searchTerm.trim.equalsIgnoreCase(e.str)))) &&
        !filteredIds.contains(user.id.str) &&
        (showBlockedUsers || (user.connection != ConnectionStatus.Blocked))
  }

}

object UserSearchService {
  type Filter = String

  val MinCommonConnections = 4
  val MaxTopPeople = 10
}
