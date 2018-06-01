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

import java.io.{BufferedOutputStream, FileOutputStream}
import java.net.URL
import java.security.{DigestOutputStream, MessageDigest}

import android.util.Base64
import com.waz.api.impl.ErrorResponse
import com.waz.api.impl.ProgressIndicator.{Callback, ProgressData}
import com.waz.cache.{CacheEntry, CacheService, Expiration, LocalData}
import com.waz.model.{Mime, _}
import com.waz.service.BackendConfig
import com.waz.utils.{IoUtils, JsonDecoder, JsonEncoder}
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet2.http
import com.waz.znet2.http.HttpClient.dsl._
import com.waz.znet2.http.HttpClient.{Progress, ProgressCallback}
import com.waz.znet2.http._
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.concurrent.duration._

trait AssetClient {
  import com.waz.sync.client.AssetClient._

  //TODO Request should be constructed inside "*Client" classes
  def loadAsset[T: RequestSerializer](
                                       req: http.Request[T],
                                       key: Option[AESKey] = None,
                                       sha: Option[Sha256] = None,
                                       callback: Callback
                                     ): ErrorOrResponse[CacheEntry]

  //TODO Add callback parameter
  def uploadAsset(metadata: Metadata, data: LocalData, mime: Mime): ErrorOrResponse[UploadResponse]
}

class AssetClientImpl(private val cacheService: CacheService,
                      private val backend: BackendConfig)
                     (implicit
                      private val client: HttpClient,
                      private val authRequestInterceptor: RequestInterceptor = RequestInterceptor.identity) extends AssetClient {

  import AssetClient._

  private def cacheEntryBodyDeserializer(key: Option[AESKey], sha: Option[Sha256]): RawBodyDeserializer[CacheEntry] =
    RawBodyDeserializer.create[CacheEntry] { body =>
      val entry = cacheService.createManagedFile(key)
      val out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(entry.cacheFile)), MessageDigest.getInstance("SHA-256"))
      IoUtils.copy(body.data, out)
      if (sha.exists(_ != Sha256(out.getMessageDigest.digest()))) {
        throw new IllegalArgumentException(s"SHA256 not match. \nExpected: $sha \nCurrent: ${Sha256(out.getMessageDigest.digest())}")
      }

      entry
    }

  private def localDataRawBodySerializer(mime: Mime): RawBodySerializer[LocalData] =
    RawBodySerializer.create { data =>
      RawBody(mediaType = Some(mime.str), data.inputStream, dataLength = Some(data.length))
    }

  //TODO Get rid of this conversion
  private def convertProgressData(data: Progress): ProgressData = {
    data match {
      case p @ Progress(progress, Some(total)) if p.isCompleted =>
        ProgressData(progress, total, com.waz.api.ProgressIndicator.State.COMPLETED)
      case Progress(progress, Some(total)) =>
        ProgressData(progress, total, com.waz.api.ProgressIndicator.State.RUNNING)
      case Progress(_, None) =>
        ProgressData.Indefinite
    }
  }

  override def loadAsset[T: RequestSerializer](request: http.Request[T],
                                               key: Option[AESKey] = None,
                                               sha: Option[Sha256] = None,
                                               callback: Callback): ErrorOrResponse[CacheEntry] = {
    val progressCallback: ProgressCallback = progress => callback(convertProgressData(progress))
    implicit val bodyDeserializer: RawBodyDeserializer[CacheEntry] = cacheEntryBodyDeserializer(key, sha)

    Prepare(request)
      .withDownloadCallback(progressCallback)
      .withResultType[CacheEntry]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

  override def uploadAsset(metadata: Metadata, data: LocalData, mime: Mime): ErrorOrResponse[UploadResponse] = {
//    val progressCallback: ProgressCallback = progress => callback(convertProgressData(progress))
    implicit val rawBodySerializer: RawBodySerializer[LocalData] = localDataRawBodySerializer(mime)
    val metadataPart = BodyPart(metadata)
    val dataPart = BodyPart(data, Headers.create("Content-MD5" -> md5(data)))
    val request = http.Request.create(
      url = new URL(backend.baseUrl.toString + AssetClient.AssetsV3Path),
      method = http.Method.Post,
      body = MultipartBody(List(metadataPart, dataPart))
    )
    Prepare(request)
//      .withUploadCallback(progressCallback)
      .withResultType[UploadResponse]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

}

object AssetClient {

  implicit val DefaultExpiryTime: Expiration = 1.hour

  val AssetsV3Path = "/assets/v3"

  sealed abstract class Retention(val value: String)
  object Retention {
    case object Eternal extends Retention("eternal") //Only used for profile pics currently
    case object EternalInfrequentAccess extends Retention("eternal-infrequent_access")
    case object Persistent extends Retention("persistent")
    case object Expiring extends Retention("expiring")
    case object Volatile extends Retention("volatile")
  }

  case class Metadata(public: Boolean = false, retention: Retention = Retention.Persistent)

  object Metadata {
    implicit val jsonEncoder: JsonEncoder[Metadata] = JsonEncoder.build[Metadata] { metadata => o =>
      o.put("public", metadata.public)
      o.put("retention", metadata.retention.value)
    }
  }

  case class UploadResponse(rId: RAssetId, expires: Option[Instant], token: Option[AssetToken])

  case object UploadResponse {
    implicit val jsonDecoder: JsonDecoder[UploadResponse] = new JsonDecoder[UploadResponse] {
      import JsonDecoder._
      override def apply(implicit js: JSONObject): UploadResponse = {
        UploadResponse(RAssetId('key), decodeOptISOInstant('expires), decodeOptString('token).map(AssetToken))
      }
    }
  }

  def postAssetPath(conv: RConvId) = s"/conversations/$conv/assets"

  def getAssetPath(rId: RAssetId, otrKey: Option[AESKey], conv: Option[RConvId]): String = {
    (conv, otrKey) match {
      case (None, _)          => s"/assets/v3/${rId.str}"
      case (Some(c), None)    => s"/conversations/${c.str}/assets/${rId.str}"
      case (Some(c), Some(_)) => s"/conversations/${c.str}/otr/assets/${rId.str}"
    }
  }

  /**
    * Computes base64 encoded md5 sum of image data.
    */
  def md5(data: LocalData): String = Base64.encodeToString(IoUtils.md5(data.inputStream), Base64.NO_WRAP)

}

