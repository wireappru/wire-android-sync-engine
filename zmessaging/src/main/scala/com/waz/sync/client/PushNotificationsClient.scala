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
package com.waz.sync.client

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service.BackendConfig
import com.waz.sync.client.PushNotificationsClient.LoadNotificationsResponse
import com.waz.utils.JsonDecoder.arrayColl
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet.{JsonArrayResponse, JsonObjectResponse, ResponseContent}
import com.waz.znet2.AuthRequestInterceptor
import com.waz.znet2.http.{HttpClient, RawBodyDeserializer, Request}
import org.json.{JSONArray, JSONObject}
import org.threeten.bp.Instant

import scala.util.control.NonFatal

trait PushNotificationsClient {
  def loadNotifications(since: Option[Uid], client: ClientId): ErrorOrResponse[LoadNotificationsResponse]
  def loadLastNotification(clientId: ClientId): ErrorOrResponse[LoadNotificationsResponse]
}

class PushNotificationsClientImpl(private val pageSize: Int = PushNotificationsClient.PageSize)
                                 (implicit
                                  private val backendConfig: BackendConfig,
                                  private val httpClient: HttpClient,
                                  private val authRequestInterceptor: AuthRequestInterceptor) extends PushNotificationsClient {

  import BackendConfig.backendUrl
  import HttpClient.dsl._
  import PushNotificationsClient._

  private implicit val loadNotifResponseDeserializer: RawBodyDeserializer[LoadNotificationsResponse] =
    RawBodyDeserializer[JSONObject].map(json => PagedNotificationsResponse.unapply(JsonObjectResponse(json)).get)

  override def loadNotifications(since: Option[Uid], client: ClientId): ErrorOrResponse[LoadNotificationsResponse] = {
    val request = Request.withoutBody(url = backendUrl(notificationsPath(client, since, Some(pageSize))))
    Prepare(request)
      .withResultType[LoadNotificationsResponse]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def loadLastNotification(clientId: ClientId): ErrorOrResponse[LoadNotificationsResponse] = {
    val request = Request.withoutBody(url = backendUrl(notificationsPath(clientId)))
    Prepare(request)
      .withResultType[LoadNotificationsResponse]
      .withErrorType[ErrorResponse]
      .executeSafe
  }
}

object PushNotificationsClient {

  val NotificationsPath = "/notifications"
  val NotificationsLastPath = "/notifications/last"
  val PageSize = 500

  def notificationsPath(client: ClientId, since: Option[Uid] = None, pageSize: Option[Int] = None) = {
    val args = Seq("since" -> since, "client" -> Some(client), "size" -> pageSize) collect { case (key, Some(v)) => key -> v }
    com.waz.znet.Request.query(NotificationsPath, args: _*)
  }

  case class LoadNotificationsResponse(notifications: Vector[PushNotificationEncoded], hasMore: Boolean, beTime: Option[Instant])

  object PagedNotificationsResponse {

    import com.waz.utils.JsonDecoder._

    def unapply(response: ResponseContent): Option[LoadNotificationsResponse] = try response match {
      case JsonObjectResponse(js) if js.has("notifications") =>
        Some(
          LoadNotificationsResponse(
            arrayColl[PushNotificationEncoded, Vector](js.getJSONArray("notifications")),
            decodeBool('has_more)(js),
            decodeOptISOInstant('time)(js)
          )
        )
      case JsonArrayResponse(js) =>
        Some(
          LoadNotificationsResponse(
            arrayColl[PushNotificationEncoded, Vector](js),
            hasMore = false,
            None)
        )
      case _ => None
    } catch {
      case NonFatal(e) =>
        warn(s"couldn't parse paged push notifications from response: $response", e)
        None
    }
  }

  object NotificationsResponseEncoded {
    def unapplySeq(response: ResponseContent): Option[Seq[PushNotificationEncoded]] = try response match {
      case JsonObjectResponse(js) => Some(Vector(implicitly[JsonDecoder[PushNotificationEncoded]].apply(js)))
      case JsonArrayResponse(js) => Some(arrayColl[PushNotificationEncoded, Vector](js))
      case _ => None
    } catch {
      case NonFatal(e) =>
        warn(s"couldn't parse push notification(s) from response: $response", e)
        None
    }
  }
}

case class PushNotificationEncoded(id: Uid, events: JSONArray, transient: Boolean = false)

case class PushNotification(id: Uid, events: Seq[Event], transient: Boolean = false) {

  /**
    * Check if notification contains events intended for current client. In some (rare) cases it may happen that
    * BE sends us notifications intended for different device, we can verify that by checking recipient field.
    * Unencrypted events are always considered to belong to us.
    */
  def hasEventForClient(clientId: ClientId) = events.forall(forUs(clientId, _))

  def eventsForClient(clientId: ClientId) = events.filter(forUs(clientId, _))

  private def forUs(clientId: ClientId, event: Event) = event match {
    case ev: OtrEvent => clientId == ev.recipient
    case _ => true
  }
}

object PushNotification {
  implicit lazy val NotificationDecoder: JsonDecoder[PushNotification] = new JsonDecoder[PushNotification] {
    import com.waz.utils.JsonDecoder._

    override def apply(implicit js: JSONObject): PushNotification =
      PushNotification('id, array[Event](js.getJSONArray("payload")), 'transient)
  }
}

object PushNotificationEncoded {
  implicit lazy val NotificationDecoder: JsonDecoder[PushNotificationEncoded] =
    new JsonDecoder[PushNotificationEncoded] {
      import com.waz.utils.JsonDecoder._

    override def apply(implicit js: JSONObject): PushNotificationEncoded =
      PushNotificationEncoded('id, js.getJSONArray("payload"), 'transient)
  }
  implicit lazy val NotificationEncoder: JsonEncoder[PushNotificationEncoded] = new JsonEncoder[PushNotificationEncoded] {
    override def apply(v: PushNotificationEncoded): JSONObject = JsonEncoder { o =>
      o.put("id", v.id.str)
      o.put("payload", v.events)
      o.put("transient", v.transient)
    }
  }
}
