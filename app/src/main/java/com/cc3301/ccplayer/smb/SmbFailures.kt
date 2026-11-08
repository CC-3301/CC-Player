package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
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

/** 会话或连接已失效的服务器状态：这类失败重建客户端才可能恢复。 */
private val CONNECTION_LOST_STATUSES = setOf(
    NtStatus.STATUS_USER_SESSION_DELETED,
    NtStatus.STATUS_NETWORK_NAME_DELETED,
    NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
    NtStatus.STATUS_CONNECTION_DISCONNECTED,
    NtStatus.STATUS_CONNECTION_RESET,
    NtStatus.STATUS_IO_TIMEOUT,
    NtStatus.STATUS_REQUEST_NOT_ACCEPTED,
    NtStatus.STATUS_FILE_CLOSED,
)

/**
 * 该失败是否说明连接或会话已失效、值得重建客户端。
 * 计入：传输层断开（Unreachable）、SMBJ 的运行时异常家族，以及服务器报会话或连接失效的状态码。
 * 不计入：文件不存在、权限不足、路径非法这类业务失败，以及未归类的运行时异常——
 * 重建客户端解决不了它们，却会作废一条可能正被别的消费者共用的健康会话。
 */
fun smbConnectionLost(failure: Throwable): Boolean {
    if (failure is MediaSourceException) {
        when (failure.failure) {
            MediaFailure.Unreachable -> return true
            MediaFailure.Authentication, MediaFailure.NotFound -> return false
            MediaFailure.Unknown -> Unit
        }
    }
    // 按整条异常链判：smbj 把等待超时包进传输异常，只看最内层会漏掉它。
    var current: Throwable? = failure
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        when (current) {
            is SMBApiException -> return current.status in CONNECTION_LOST_STATUSES
            is SMBRuntimeException, is IOException -> return true
        }
        current = current.cause
    }
    return false
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
