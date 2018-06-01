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
package com.waz.znet

import java.net.URL

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.Credentials
import com.waz.api.impl.ErrorResponse
import com.waz.model.AccountData.Label
import com.waz.model.{TeamId, UserInfo}
import com.waz.model2.transport.Team
import com.waz.model2.transport.responses.TeamsResponse
import com.waz.service.BackendConfig
import com.waz.service.ZMessaging.clock
import com.waz.service.tracking.TrackingService
import com.waz.sync.client.TeamsClient.teamsPaginatedQuery
import com.waz.sync.client.UsersClient
import com.waz.threading.CancellableFuture.CancelException
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.{ExponentialBackoff, JsonEncoder, _}
import com.waz.znet.AuthenticationManager._
import com.waz.znet.LoginClient.LoginResult
import com.waz.znet.Response.{Headers, Status}
import com.waz.znet.ZNetClient.ErrorOr
import com.waz.znet2.http.HttpClient
import com.waz.znet2.http.HttpClient.dsl._
import com.waz.znet2.http
import org.json.JSONObject
import org.threeten.bp

import scala.concurrent.Future
import scala.concurrent.duration._

trait LoginClient {
  def access(cookie: Cookie, token: Option[AccessToken]): ErrorOr[LoginResult]
  def login(credentials: Credentials): ErrorOr[LoginResult]
  def getSelfUserInfo(token: AccessToken): ErrorOr[UserInfo]

  def findSelfTeam(accessToken: AccessToken, start: Option[TeamId] = None): ErrorOr[Option[Team]]
  def getTeams(accessToken: AccessToken, start: Option[TeamId]): ErrorOr[TeamsResponse]
}

class LoginClientImpl(backend: BackendConfig, tracking: TrackingService)(implicit val client: HttpClient) extends LoginClient {
  import com.waz.znet.LoginClient._
  private implicit val dispatcher = new SerialDispatchQueue(name = "LoginClient")

  private[znet] var lastRequestTime = 0L
  private[znet] var failedAttempts = 0
  private var lastResponseCode = http.ResponseCode.Success
  private var loginFuture: ErrorOr[LoginResult] = CancellableFuture.successful(Left(ErrorResponse.Cancelled))

  def requestDelay =
    if (failedAttempts == 0) Duration.Zero
    else {
      val minDelay = if (lastResponseCode == Status.RateLimiting || lastResponseCode == Status.LoginRateLimiting) 5.seconds else Duration.Zero
      val nextRunTime = lastRequestTime + Throttling.delay(failedAttempts, minDelay).toMillis
      math.max(nextRunTime - System.currentTimeMillis(), 0).millis
    }

  override def login(credentials: Credentials): ErrorOr[LoginResult] = throttled(loginNow(credentials))

  override def access(cookie: Cookie, token: Option[AccessToken]) = throttled(accessNow(cookie, token))

  def throttled(request: => ErrorOr[LoginResult]): ErrorOr[LoginResult] = dispatcher {
    loginFuture = loginFuture.recover {
      case e: CancelException => Left(ErrorResponse.Cancelled)
      case ex: Throwable =>
        tracking.exception(ex, "Unexpected error when trying to log in.")
        Left(ErrorResponse.internalError("Unexpected error when trying to log in: " + ex.getMessage))
    } flatMap { _ =>
      verbose(s"throttling, delay: $requestDelay")
      CancellableFuture.delay(requestDelay).future
    } flatMap { _ =>
      verbose(s"starting request")
      lastRequestTime = System.currentTimeMillis()
      request.map {
        case Left(error) =>
          failedAttempts += 1
          lastResponseCode = error.code
          Left(error)
        case resp =>
          failedAttempts = 0
          lastResponseCode = Status.Success
          resp
      }
    }
    loginFuture
  }.future.flatten

  private implicit val FIXED_AccessTokenDecoder: JsonDecoder[AccessToken] = new JsonDecoder[AccessToken] {
    import JsonDecoder._
    override def apply(implicit js: JSONObject): AccessToken =
      AccessToken(
        'access_token,
        'token_type,
        clock.instant() + bp.Duration.ofMillis(('expires_in: Long) * 1000)
      )
  }

  def loginNow(credentials: Credentials): ErrorOr[LoginResult] = {
    debug(s"trying to login with credentials: $credentials")
    val label = Label()
    val params = JsonEncoder { o =>
      credentials.addToLoginJson(o)
      o.put("label", label.str)
    }
    val request = http.Request.create(url = new URL(backend.baseUrl.toString + LoginUriStr), body = params)
    Prepare(request).withResultType[http.Response[AccessToken]].withErrorType[ErrorResponse].executeSafe
      .map { _.right.map(resp => LoginResult(resp.body, resp.headers, Some(label))) }
      .future
  }

  def accessNow(cookie: Cookie, token: Option[AccessToken]): ErrorOr[LoginResult] = {
    val headers = token.fold(Request.EmptyHeaders)(_.headers) ++ cookie.headers
    val request = http.Request.create(
      url = new URL(backend.baseUrl.toString + AccessPath),
      method = http.Method.Post,
      headers = http.Headers(headers),
      body = ""
    )
    Prepare(request).withResultType[http.Response[AccessToken]].withErrorType[ErrorResponse].executeSafe
      .map { _.right.map(resp => LoginResult(resp.body, resp.headers, None)) }
      .future
  }

  override def getSelfUserInfo(token: AccessToken): ErrorOr[UserInfo] = {
    val request = http.Request.withoutBody(
      url = new URL(backend.baseUrl.toString + UsersClient.SelfPath),
      headers = http.Headers.create(token.headers)
    )
    Prepare(request).withResultType[UserInfo].withErrorType[ErrorResponse].executeSafe.future
  }

  override def findSelfTeam(accessToken: AccessToken, start: Option[TeamId] = None): ErrorOr[Option[Team]] =
    getTeams(accessToken, start).flatMap {
      case Left(err) => Future.successful(Left(err))
      case Right(TeamsResponse(hasMore, teams)) =>
        teams.find(_.binding) match {
          case Some(team) => Future.successful(Right(Some(team)))
          case None if hasMore => findSelfTeam(accessToken, teams.lastOption.map(_.id))
          case None => Future.successful(Right(None))
        }
    }

  override def getTeams(token: AccessToken, start: Option[TeamId]): ErrorOr[TeamsResponse] = {
    val request = http.Request.withoutBody(
      url = new URL(backend.baseUrl.toString + teamsPaginatedQuery(start)),
      headers = http.Headers.create(token.headers)
    )
    Prepare(request).withResultType[TeamsResponse].withErrorType[ErrorResponse].executeSafe.future
  }

}

object LoginClient {

  case class LoginResult(accessToken: AccessToken, cookie: Option[Cookie], label: Option[Label])

  object LoginResult {

    def apply(accessToken: AccessToken, headers: http.Headers, label: Option[Label]): LoginResult =
      new LoginResult(accessToken, getCookieFromHeaders(headers), label)

  }

  val SetCookie = "Set-Cookie"
  val Cookie = "Cookie"
  val CookieHeader = ".*zuid=([^;]+).*".r
  val LoginPath = "/login"
  val AccessPath = "/access"
  val ActivateSendPath = "/activate/send"
  val LoginUriStr = Request.query(LoginPath, ("persist", true))

  val Throttling = new ExponentialBackoff(1000.millis, 10.seconds)

  def getCookieFromHeaders(headers: http.Headers): Option[Cookie] = headers.get(SetCookie) flatMap {
    case header @ CookieHeader(cookie) =>
      verbose(s"parsed cookie from header: $header, cookie: $cookie")
      Some(AuthenticationManager.Cookie(cookie))
    case header =>
      warn(s"Unexpected content for Set-Cookie header: $header")
      None
  }

  def getCookieFromHeaders(headers: Headers): Option[Cookie] = headers(SetCookie) flatMap {
    case header @ CookieHeader(cookie) =>
      verbose(s"parsed cookie from header: $header, cookie: $cookie")
      Some(AuthenticationManager.Cookie(cookie))
    case header =>
      warn(s"Unexpected content for Set-Cookie header: $header")
      None
  }

}
