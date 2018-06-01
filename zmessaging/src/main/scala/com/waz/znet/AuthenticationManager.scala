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

import com.koushikdutta.async.http.AsyncHttpRequest
import com.waz.ZLog._
import com.waz.api.EmailCredentials
import com.waz.api.impl.ErrorResponse
import com.waz.content.AccountStorage
import com.waz.model.{AccountData, UserId}
import com.waz.service.ZMessaging.{accountTag, clock}
import com.waz.service.tracking.TrackingService
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.JsonEncoder.encodeInstant
import com.waz.utils.{JsonDecoder, JsonEncoder, _}
import com.waz.znet.AuthenticationManager.AccessToken
import com.waz.znet.LoginClient.LoginResult
import com.waz.znet.Response._
import com.waz.znet.ZNetClient.ErrorOr
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._

trait AccessTokenProvider {
  def currentToken(): ErrorOr[AccessToken]

  //If the user has recently provided a new password, supply it here so that we can attempt to get a new cookie and avoid them being logged out
  def onPasswordReset(emailCredentials: Option[EmailCredentials] = None): ErrorOr[Unit]
}

/**
 * Manages authentication token, and dispatches login requests when needed.
 * Will retry login request if unsuccessful.
 */
class AuthenticationManager(id: UserId, accStorage: AccountStorage, client: LoginClient, tracking: TrackingService) extends AccessTokenProvider {

  lazy implicit val logTag: LogTag = accountTag[AuthenticationManager](id)

  import com.waz.znet.AuthenticationManager._

  implicit val dispatcher = new SerialDispatchQueue(name = "AuthenticationManager")

  private def token  = withAccount(_.accessToken)
  private def cookie = withAccount(_.cookie)

  private def withAccount[A](f: (AccountData) => A): Future[A] = {
    accStorage.get(id).map {
      case Some(acc) => f(acc)
      case _         => throw LoggedOutException
    }
  }

  //Only performs safe update - never wipes either the cookie or the token.
  private def updateCredentials(token: Option[AccessToken] = None, cookie: Option[Cookie] = None) = {
    verbose(s"updateCredentials: $token, $cookie")
    accStorage.update(id, acc => acc.copy(accessToken = if (token.isDefined) token else acc.accessToken, cookie = cookie.getOrElse(acc.cookie)))
  }

  private def wipeCredentials() = {
    verbose("wipe credentials")
    accStorage.remove(id)
  }

  def invalidateToken() = token.map(_.foreach(t => updateCredentials(Some(t.copy(expiresAt = Instant.EPOCH)))))

  def isExpired(token: AccessToken) = (token.expiresAt - ExpireThreshold) isBefore clock.instant()

  /**
   * Returns current token if not expired or performs access request. Failing that, the user gets logged out
   */
  override def currentToken() = returning(Serialized.future("login-client") {
    verbose("currentToken")
    token.flatMap {
      case Some(token) if !isExpired(token) =>
        verbose(s"Non expired token: $token")
        Future.successful(Right(token))
      case token => cookie.flatMap { cookie =>
        debug(s"Non existent or potentially expired token: $token, will attempt to refresh with cookie: $cookie")
        dispatchRequest(client.access(cookie, token)) {
          case Left(resp@ErrorResponse(Status.Forbidden | Status.Unauthorized, message, label)) =>
            verbose(s"access request failed (label: $label, message: $message), will try login request. currToken: $token, cookie: $cookie, access resp: $resp")
            tracking.exception(new RuntimeException(s"Access request failed: msg: $message, label: $label, cookie expired at: ${cookie.expiry} (is valid: ${cookie.isValid}), currToken expired at: ${token.map(_.expiresAt)} (is valid: ${token.exists(_.isValid)})"), null)
            wipeCredentials().map(_ => Left(resp))
        }
      }
    }
  }.recover {
    case LoggedOutException =>
      warn(s"Request failed as we are logged out")
      Left(ErrorResponse.Unauthorized)
  })(_.failed.foreach(throw _))

  override def onPasswordReset(emailCredentials: Option[EmailCredentials]): ErrorOr[Unit] =
    Serialized.future("login-client") {
      verbose(s"onPasswordReset: $emailCredentials")
      cookie.flatMap { cookie =>
        debug(s"Attempting access request to see if cookie is still valid: $cookie")
        dispatchRequest(client.access(cookie, None)) {
          case Left(resp @ ErrorResponse(Status.Forbidden | Status.Unauthorized, _, _)) =>
            emailCredentials match {
              case Some(credentials) =>
                client.login(credentials).flatMap {
                  case Right(LoginResult(token, c, _)) => updateCredentials(Some(token), c).map(_ => Right(token))
                  case Left(resp @ ErrorResponse(Status.Forbidden | Status.Unauthorized, _, _)) => wipeCredentials().map(_ => Left(resp)) //credentials didn't match - log user out
                  case Left(err) => Future.successful(Left(err))
                }
              case None =>
                verbose(s"Cookie is now invalid, and no credentials were supplied. The user will now be logged out")
                wipeCredentials().map(_ => Left(resp))
            }
        }.map(_.right.map(_ => ()))
      }
    }

  private def dispatchRequest(request: => ErrorOr[LoginResult], retryCount: Int = 0)(handler: ResponseHandler): ErrorOr[AccessToken] =
    request.flatMap(handler.orElse {
      case Right(LoginResult(token, cookie, _)) =>
        debug(s"receivedAccessToken: '$token'")
        updateCredentials(Some(token), cookie).map(_ => Right(token))

      case Left(err @ ErrorResponse(Cancelled.status, msg, label)) =>
        debug(s"request has been cancelled")
        Future.successful(Left(err))

      case Left(err) if retryCount < MaxRetryCount =>
        info(s"Received error from request: $err, will retry")
        dispatchRequest(request, retryCount + 1)(handler)

      case Left(err) =>
        val msg = s"Login request failed after $retryCount retries, last status: $err"
        error(msg)
        Future.successful(Left(err))
    })
}

object AuthenticationManager {

  case object LoggedOutException extends RuntimeException

  val MaxRetryCount = 3
  val ExpireThreshold = 15.seconds // refresh access token on background if it is close to expire

  type ResponseHandler = PartialFunction[Either[ErrorResponse, LoginResult], ErrorOr[AccessToken]]

  case class Cookie(str: String) {

    private val parts = str.split('.').toSet
    val headers = Map(LoginClient.Cookie -> s"zuid=$str")
    val expiry = find("d=").map(v => Instant.ofEpochSecond(v.toLong))
    def isValid = expiry.exists(_.isAfter(Instant.now))

    private def find(pref: String) = parts.find(_.contains(pref)).map(_.drop(2))

    override def toString = s"${str.take(10)}, exp: $expiry, isValid: $isValid"
  }

  case class AccessToken(accessToken: String, tokenType: String, expiresAt: Instant = Instant.EPOCH) {
    val headers = Map(AccessToken.AuthorizationHeader -> s"$tokenType $accessToken")
    def prepare(req: AsyncHttpRequest) = req.addHeader(AccessToken.AuthorizationHeader, s"$tokenType $accessToken")

    def isValid = expiresAt isAfter clock.instant()

    override def toString = s"${accessToken.take(10)}, exp: $expiresAt, isValid: $isValid"
  }

  object AccessToken extends ((String, String, Instant) => AccessToken ){
    val AuthorizationHeader = "Authorization"

    implicit lazy val Encoder: JsonEncoder[AccessToken] = new JsonEncoder[AccessToken] {
      override def apply(v: AccessToken): JSONObject = JsonEncoder { o =>
        o.put("token", v.accessToken)
        o.put("type", v.tokenType)
        o.put("expires", encodeInstant(v.expiresAt))
      }
    }

    implicit lazy val Decoder: JsonDecoder[AccessToken] = new JsonDecoder[AccessToken] {
      import JsonDecoder._
      override def apply(implicit js: JSONObject): AccessToken = AccessToken('token, 'type, 'expires)
    }
  }
}
