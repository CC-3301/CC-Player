package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.common.SMBRuntimeException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 把 SMBJ 抛出的异常归到界面可读的来源失败原因。 */
fun smbFailureOf(failure: Throwable): MediaFailure = when (failure) {
    is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> MediaFailure.Unreachable
    is SMBApiException -> statusFailure(failure.status)
    is SMBRuntimeException -> failure.cause?.let(::smbFailureOf) ?: MediaFailure.Unknown
    is IOException -> MediaFailure.Unreachable
    else -> MediaFailure.Unknown
}

private fun statusFailure(status: NtStatus?): MediaFailure = when (status) {
    NtStatus.STATUS_LOGON_FAILURE,
    NtStatus.STATUS_ACCESS_DENIED,
    NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
    -> MediaFailure.Authentication

    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
    NtStatus.STATUS_BAD_NETWORK_NAME,
    NtStatus.STATUS_BAD_NETWORK_PATH,
    NtStatus.STATUS_NOT_FOUND,
    -> MediaFailure.NotFound

    else -> MediaFailure.Unknown
}
