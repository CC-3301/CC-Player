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

/** 一次 SMB 失败该作废到哪一层。 */
enum class SmbFailureScope {
    /** 不是传输失败：认证、路径与业务失败，作废解决不了。 */
    None,

    /** 会话或树连接失效：只重建会话，传输继续用。 */
    Session,

    /** 本地传输失效：这条服务器＋端口的传输不再复用，换掉它才能恢复。 */
    Transport,
}

/**
 * 判定一次失败该作废到哪一层。
 *
 * 会话失效由服务器报的状态码指出；信用异常、IO 异常与停滞等本地传输失效按传输层异常判。
 * 认证、路径与业务失败不作废。建连失败没有旧传输可换，由调用方按网络／服务器终态报给使用者，
 * 不参与这里的分类。
 */
fun smbFailureScope(failure: Throwable): SmbFailureScope {
    if (failure is MediaSourceException) {
        when (failure.failure) {
            MediaFailure.Authentication, MediaFailure.NotFound -> return SmbFailureScope.None
            MediaFailure.Unreachable, MediaFailure.Unknown -> Unit
        }
    }
    // 按整条异常链判：smbj 把等待超时包进传输异常，只看最内层会漏掉它。
    // 来源失败异常自己也是 IO 异常，先看它包着的原因，别被最外层类型带走。
    var current: Throwable? = if (failure is MediaSourceException) failure.cause else failure
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        when (current) {
            is SMBApiException -> return statusScope(current.status)
            is IOException, is SMBRuntimeException -> return SmbFailureScope.Transport
        }
        current = current.cause
    }
    // 原因链上没有更具体的失败：来源失败、IO 与运行时异常都按本地传输失效处理。
    return when (failure) {
        is MediaSourceException, is IOException, is SMBRuntimeException -> SmbFailureScope.Transport
        else -> SmbFailureScope.None
    }
}

/**
 * 该失败是否说明连接或会话已失效、值得作废。
 * 计入：会话与树连接失效、本地传输失效。
 * 不计入：文件不存在、权限不足、路径非法这类业务失败，以及未归类的运行时异常——
 * 作废解决不了它们，却会拆掉一条可能正被别的消费者共用的传输。
 */
fun smbConnectionLost(failure: Throwable): Boolean = smbFailureScope(failure) != SmbFailureScope.None

/** 服务器状态码对应的作废范围；两类都指不上的按业务失败处理。 */
private fun statusScope(status: NtStatus?): SmbFailureScope = when (status) {
    NtStatus.STATUS_USER_SESSION_DELETED,
    NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
    NtStatus.STATUS_NETWORK_NAME_DELETED,
    NtStatus.STATUS_FILE_CLOSED,
    -> SmbFailureScope.Session

    NtStatus.STATUS_CONNECTION_DISCONNECTED,
    NtStatus.STATUS_CONNECTION_RESET,
    NtStatus.STATUS_IO_TIMEOUT,
    NtStatus.STATUS_REQUEST_NOT_ACCEPTED,
    -> SmbFailureScope.Transport

    else -> SmbFailureScope.None
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
