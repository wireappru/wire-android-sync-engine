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

import java.net.URL

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service.BackendConfig
import com.waz.sync.client.ConnectionsClient.PageSize
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.JsonDecoder._
import com.waz.utils.{Json, _}
import com.waz.znet.ZNetClient._
import com.waz.znet.{JsonObjectResponse, ResponseContent}
import com.waz.znet2.AuthRequestInterceptor
import com.waz.znet2.http.HttpClient.HttpClientError
import com.waz.znet2.http.{HttpClient, Method, RawBodyDeserializer, Request}
import org.json.JSONObject

import scala.util.Try
import scala.util.control.NonFatal

trait ConnectionsClient {
  def loadConnections(start: Option[UserId] = None, pageSize: Int = PageSize): ErrorOrResponse[Seq[UserConnectionEvent]]
  def loadConnection(id: UserId): ErrorOrResponse[UserConnectionEvent]
  def createConnection(user: UserId, name: String, message: String): ErrorOrResponse[UserConnectionEvent]
  def updateConnection(user: UserId, status: ConnectionStatus): ErrorOrResponse[Option[UserConnectionEvent]]
}

class ConnectionsClientImpl(private val backendConfig: BackendConfig)
                           (implicit
                            private val httpClient: HttpClient,
                            private val authRequestInterceptor: AuthRequestInterceptor) extends ConnectionsClient {

  import HttpClient.dsl._
  import com.waz.sync.client.ConnectionsClient._
  import Threading.Implicits.Background

  private implicit val UserConnectionEventDeserializer: RawBodyDeserializer[UserConnectionEvent] =
    RawBodyDeserializer[JSONObject].map(json => ConnectionResponseExtractor.unapply(JsonObjectResponse(json)).get)

  private implicit val UserConnectionsEventDeserializer: RawBodyDeserializer[(Seq[UserConnectionEvent], Boolean)] =
    RawBodyDeserializer[JSONObject].map(json => ConnectionsResponseExtractor.unapply(JsonObjectResponse(json)).get)

  override def loadConnections(start: Option[UserId] = None, pageSize: Int = PageSize): ErrorOrResponse[Seq[UserConnectionEvent]] = {
    val request = Request.withoutBody(
      url = new URL(backendConfig.baseUrl.toString + ConnectionsPath),
      queryParameters = ("size" -> pageSize.toString) :: start.fold2(List.empty, s => List("start" -> s.str))
    )

    Prepare(request)
      .withResultType[(Seq[UserConnectionEvent], Boolean)]
      .withErrorType[ErrorResponse]
      .execute
      .flatMap { case (events, hasMore) =>
        if (hasMore) loadConnections(Some(events.last.to), pageSize).map { _.right.map(events ++ _) }
        else CancellableFuture.successful(Right(events))
      }
      .recover {
        case err: ErrorResponse => Left(err)
        case err: HttpClientError => Left(ErrorResponse.errorResponseConstructor.constructFrom(err))
      }
  }

  override def loadConnection(id: UserId): ErrorOrResponse[UserConnectionEvent] = {
    val request = Request.withoutBody(url = new URL(backendConfig.baseUrl.toString + s"$ConnectionsPath/$id"))
    Prepare(request)
      .withResultType[UserConnectionEvent]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def createConnection(user: UserId, name: String, message: String): ErrorOrResponse[UserConnectionEvent] = {
    val jsonData = Json("user" -> user.toString, "name" -> name, "message" -> message)
    val request = Request.create(url = new URL(backendConfig.baseUrl.toString + ConnectionsPath), body = jsonData)
    Prepare(request)
      .withResultType[UserConnectionEvent]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def updateConnection(user: UserId, status: ConnectionStatus): ErrorOrResponse[Option[UserConnectionEvent]] = {
    val jsonData = Json("status" -> status.code)
    val request = Request.create(
      url = new URL(backendConfig.baseUrl.toString + ConnectionsPath),
      method = Method.Put,
      body = jsonData
    )

    Prepare(request)
      .withResultType[Option[UserConnectionEvent]]
      .withErrorType[ErrorResponse]
      .executeSafe
  }
}

object ConnectionsClient {
  val ConnectionsPath = "/connections"
  val PageSize = 100

  object ConnectionResponseExtractor {
    def unapply(resp: ResponseContent): Option[UserConnectionEvent] = resp match {
      case JsonObjectResponse(js) => Try(UserConnectionEvent.Decoder(js)).toOption
      case _ =>
        warn(s"unknown response format for connection response: $resp")
        None
    }
  }

  object ConnectionsResponseExtractor {
    def unapply(response: ResponseContent): Option[(List[UserConnectionEvent], Boolean)] = try {
      response match {
        case JsonObjectResponse(js) =>
          Some((if (js.has("connections")) array[UserConnectionEvent](js.getJSONArray("connections")).toList else Nil, decodeBool('has_more)(js)))
        case _ =>
          warn(s"unknown response format for connections response: $response")
          None
      }
    } catch {
      case NonFatal(e) =>
        warn(s"couldn't parse connections response: $response", e)
        None
    }
  }
}
