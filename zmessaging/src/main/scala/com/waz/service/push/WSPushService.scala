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
package com.waz.service.push

import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.model.UserId
import com.waz.model.otr.ClientId
import com.waz.service.ZMessaging.accountTag
import com.waz.service.push.WSPushServiceImpl.RequestCreator
import com.waz.service.{AccountContext, BackendConfig}
import com.waz.sync.client.PushNotificationEncoded
import com.waz.sync.client.PushNotificationsClient.NotificationsResponseEncoded
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils.events._
import com.waz.utils.wrappers.URI
import com.waz.utils.{Backoff, ExponentialBackoff}
import com.waz.znet.AuthenticationManager.AccessToken
import com.waz.znet.{AccessTokenProvider, AsyncClient}
import com.waz.znet2.WebSocketFactory.SocketEvent
import com.waz.znet2.{WebSocket, WebSocketFactory, http}

import scala.concurrent.duration._
import scala.util.Left

trait WSPushService {
  def activate(): Unit
  def deactivate(): Unit
  def notifications(): EventStream[Seq[PushNotificationEncoded]]
  def connected(): Signal[Boolean]
}

object WSPushServiceImpl {

  type RequestCreator = AccessToken => http.Request[http.Body]

  def apply(userId: UserId,
            clientId: ClientId,
            backend: BackendConfig,
            webSocketFactory: WebSocketFactory,
            accessTokenProvider: AccessTokenProvider,
            ev: AccountContext): WSPushServiceImpl = {

    val requestCreator = (token: AccessToken) => {
      val uri = URI.parse(backend.websocketUrl).buildUpon.appendQueryParameter("client", clientId.str).build
      val headers = token.headers ++ Map(
        "Accept-Encoding" -> "identity", // XXX: this is a hack for Backend In The Box problem: 'Accept-Encoding: gzip' header causes 500
        AsyncClient.UserAgentHeader -> AsyncClient.userAgent()
      )

      http.Request.withoutBody(
        method = http.Method.Get,
        url = new URL(uri.toString),
        headers = http.Headers.create(headers)
      ).asInstanceOf[http.Request[http.Body]]
    }

    new WSPushServiceImpl(
      userId,
      accessTokenProvider,
      requestCreator,
      webSocketFactory,
      ExponentialBackoff.standardBackoff
    )(ev)
  }


}

class WSPushServiceImpl(userId:              UserId,
                        accessTokenProvider: AccessTokenProvider,
                        requestCreator:      RequestCreator,
                        webSocketFactory:    WebSocketFactory,
                        backoff:             Backoff = ExponentialBackoff.standardBackoff)
                       (implicit ev: EventContext) extends WSPushService {

  private implicit val logTag: LogTag = accountTag[WSPushServiceImpl](userId)
  private implicit val dispatcher: SerialDispatchQueue = new SerialDispatchQueue(name = "WSPushServiceImpl")

  override val notifications: SourceStream[Seq[PushNotificationEncoded]] = EventStream()
  override val connected: SourceSignal[Boolean] = Signal(false)

  private val activated: SourceSignal[Boolean] = Signal(false)
  override def activate(): Unit = activated ! true
  override def deactivate(): Unit = activated ! false

  private var currentWebSocketSubscription: Subscription = _

  activated.on(dispatcher) {
    case false  =>
      debug("WebSocket will be deactivated")
      finishWebSocketProcess()
    case true   =>
      debug("WebSocket will be activated")
      restartWebSocketProcess()
  }

  private val retryCount = new AtomicInteger(0)

  private def finishWebSocketProcess(): Unit = {
    verbose("Finishing websocket process.")
    if (currentWebSocketSubscription != null) {
      verbose("Current websocket subscription will be destroyed.")
      currentWebSocketSubscription.destroy()
      currentWebSocketSubscription = null
    }
  }

  private def restartWebSocketProcess(initialDelay: FiniteDuration = 0.seconds): Unit = {
    verbose("Restarting websocket process.")
    finishWebSocketProcess()
    currentWebSocketSubscription = webSocketProcessEngine(initialDelay)
  }

  private def webSocketProcessEngine(initialDelay: FiniteDuration): Subscription = {
    verbose("Constructing websocket engine subscription.")
    val events: EventStream[Either[ErrorResponse, SocketEvent]] = for {
      _ <- EventStream.wrap(Signal.future(CancellableFuture.delay(initialDelay)))
      _ = info(s"Opening WebSocket... ${if (retryCount.get() == 0) "" else s"Retry count: ${retryCount.get()}"}")
      accessTokenResult <- EventStream.wrap(Signal.future(accessTokenProvider.currentToken()))
      event <- accessTokenResult match {
        case Right(token) =>
          webSocketFactory.openWebSocket(requestCreator(token)).map(Right.apply)
        case Left(errorResponse) =>
          EventStream.wrap(Signal.const(Left(errorResponse)))
      }
    } yield event

    events.on(dispatcher) {
      case Left(errorResponse) =>
        info(s"Error while access token receiving: $errorResponse")
        restartWebSocketProcess(initialDelay = backoff.delay(retryCount.incrementAndGet()))
      case Right(SocketEvent.Opened(_)) =>
        info("WebSocket opened")
        connected ! true
        retryCount.set(0)
      case Right(SocketEvent.Closing(socket, _, _)) =>
        //ignore close code and reason. just close socket with normal code
        socket.close(WebSocket.CloseCodes.NormalClosure)
      case Right(SocketEvent.Closed(_, Some(error))) =>
        info(s"WebSocket closed with error: $error")
        restartWebSocketProcess(initialDelay = backoff.delay(retryCount.incrementAndGet()))
      case Right(SocketEvent.Closed(_, _)) =>
        info(s"WebSocket closed")
        restartWebSocketProcess(initialDelay = backoff.delay(retryCount.incrementAndGet()))
      case Right(SocketEvent.Message(_, NotificationsResponseEncoded(notifs @ _*))) =>
        info("Push notifications received")
        notifications ! notifs
      case Right(SocketEvent.Message(_, _)) =>
        error("Unknown message received")
    }
  }

}
