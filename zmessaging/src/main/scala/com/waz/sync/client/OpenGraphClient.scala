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

import com.waz.api.impl.ErrorResponse
import com.waz.sync.client.OpenGraphClient.OpenGraphData
import com.waz.utils.{JsonDecoder, JsonEncoder}
import com.waz.utils.wrappers.URI
import com.waz.znet.ZNetClient.ErrorOrResponse
import com.waz.znet._
import com.waz.znet2.http.{HttpClient, RawBodyDeserializer}
import org.json.JSONObject

import scala.util.matching.Regex

trait OpenGraphClient {
  def loadMetadata(uri: URI): ErrorOrResponse[Option[OpenGraphData]]
}

class OpenGraphClientImpl(implicit private val httpClient: HttpClient) extends OpenGraphClient {
  import OpenGraphClient._
  import com.waz.znet2.http
  import com.waz.znet2.http.HttpClient.dsl._

  private implicit val OpenGraphDataDeserializer: RawBodyDeserializer[OpenGraphData] =
    RawBodyDeserializer[String].map(bodyStr => OpenGraphDataResponse.unapply(StringResponse(bodyStr)).get)

  override def loadMetadata(uri: URI): ErrorOrResponse[Option[OpenGraphData]] = {
    val headers = Map(AsyncClient.UserAgentHeader -> DesktopUserAgent)  // using empty User-Agent to avoid getting mobile website version
    val request = http.Request.withoutBody(url = new URL(uri.toString), headers = http.Headers.create(headers))

    Prepare(request)
      .withResultType[Option[OpenGraphData]]
      .withErrorType[ErrorResponse]
      .executeSafe
  }

}

object OpenGraphClient {
  val MaxHeaderLength: Int = 16 * 1024 // maximum amount of data to load from website
  val DesktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.36"
  val CookiePattern: Regex = """([^=]+)=([^\;]+)""".r

  case class OpenGraphData(title: String, description: String, image: Option[URI], tpe: String, permanentUrl: Option[URI])

  object OpenGraphData extends ((String, String, Option[URI], String, Option[URI]) => OpenGraphData) {
    val Empty = OpenGraphData("", "", None, "", None)

    implicit object Decoder extends JsonDecoder[OpenGraphData] {
      import JsonDecoder._
      override def apply(implicit js: JSONObject): OpenGraphData =
        OpenGraphData('title, 'description, decodeOptString('image).map(URI.parse), 'tpe, decodeOptString('url).map(URI.parse))
    }

    implicit object Encoder extends JsonEncoder[OpenGraphData] {
      override def apply(v: OpenGraphData): JSONObject = JsonEncoder { o =>
        o.put("title", v.title)
        o.put("description", v.description)
        v.image foreach { uri => o.put("image", uri.toString) }
        v.permanentUrl foreach { uri => o.put("url", uri.toString) }
        o.put("tpe", v.tpe)
      }
    }
  }

  object OpenGraphDataResponse {
    val Title = "title"
    val Image = "image"
    val Type = "type"
    val Url = "url"
    val Description = "description"

    val PropertyPrefix = """^(og|twitter):(.+)""".r
    val MetaTag = """<\s*meta\s+[^>]+>""".r
    val Attribute = """(\w+)\s*=\s*("|')([^"']+)("|')""".r

    val TitlePattern = """<title[^>]*>(.*)</title>""".r

    val BaseTypes = Seq("", "article", "website", "product", "video.movie", "video.tv_show")
    val KnownSpecificTypes = Seq("instapp:photo", "ebay-objects:item", "tumblr-feed:tumblelog")
    val AcceptedTypes = BaseTypes ++ KnownSpecificTypes // will ignore other types for now

    def unapply(body: StringResponse): Option[OpenGraphData] = {

      def htmlTitle = TitlePattern.findFirstMatchIn(body.value).map(_.group(1))

      val ogMeta = MetaTag.findAllIn(body.value) .flatMap { meta =>
        val attrs = Attribute.findAllMatchIn(meta) .map { m => m.group(1).toLowerCase -> m.group(3) } .toMap
        val name = attrs.get("property").orElse(attrs.get("name"))
        val iter = PropertyPrefix.findAllMatchIn(name.getOrElse("")).map(a => a.group(2).toLowerCase -> attrs.getOrElse("content",""))
        if (iter.hasNext)
          Some(iter.next())
        else
          None
      } .toMap

      if ((ogMeta.contains(Title) || ogMeta.contains(Image)) && ogMeta.get(Type).forall { tpe => AcceptedTypes.contains(tpe.toLowerCase) }) {
        Some(OpenGraphData(ogMeta.get(Title).orElse(htmlTitle).getOrElse(""), ogMeta.getOrElse(Description, ""), ogMeta.get(Image).map(URI.parse), ogMeta.getOrElse(Type, ""), ogMeta.get(Url).map(URI.parse)))
      } else None
    }
  }
}
