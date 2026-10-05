package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 把 OkHttp 抛出的异常归到界面可读的来源失败原因。 */
fun webdavFailureOf(failure: Throwable): MediaFailure = when (failure) {
    is UnknownHostException, is ConnectException, is NoRouteToHostException,
    is SocketTimeoutException, is IOException,
    -> MediaFailure.Unreachable

    else -> MediaFailure.Unknown
}

/** 把 HTTP 状态码归到来源失败原因。 */
fun webdavStatusFailure(statusCode: Int): MediaFailure = when (statusCode) {
    STATUS_UNAUTHORIZED, STATUS_FORBIDDEN -> MediaFailure.Authentication
    STATUS_NOT_FOUND -> MediaFailure.NotFound
    else -> MediaFailure.Unknown
}

private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_FORBIDDEN = 403
private const val STATUS_NOT_FOUND = 404
