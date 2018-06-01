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
package com.waz.znet2

import com.waz.threading.CancellableFuture
import com.waz.znet.AccessTokenProvider
import com.waz.znet2.http.{Body, Headers, Request, RequestInterceptor}

class AuthRequestInterceptor(tokenProvider: AccessTokenProvider) extends RequestInterceptor {
  import com.waz.threading.Threading.Implicits.Background
  override def intercept(request: Request[Body]): CancellableFuture[Request[Body]] = {
    CancellableFuture.lift(tokenProvider.currentToken()).map {
      case Right(token) =>
        request.copy(headers = Headers.create(request.headers.headers ++ token.headers))
      case Left(err) =>
        throw new IllegalArgumentException(err.toString)
    }
  }
}
