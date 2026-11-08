package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.protocol.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SmbFailuresTest {

    @Test
    fun `连不上主机归为不可达`() {
        listOf(
            UnknownHostException("nas"),
            ConnectException("拒绝连接"),
            SocketTimeoutException("超时"),
            TransportException("链路断了"),
            IOException("读写失败"),
        ).forEach { failure ->
            assertEquals(failure.toString(), MediaFailure.Unreachable, smbFailureOf(failure))
        }
    }

    @Test
    fun `登录被拒归为凭据错误`() {
        listOf(
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_ACCESS_DENIED,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
        ).forEach { status ->
            assertEquals(status.toString(), MediaFailure.Authentication, smbFailureOf(apiException(status)))
        }
    }

    @Test
    fun `路径不存在归为找不到`() {
        listOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_BAD_NETWORK_NAME,
            NtStatus.STATUS_BAD_NETWORK_PATH,
            NtStatus.STATUS_NOT_FOUND,
        ).forEach { status ->
            assertEquals(status.toString(), MediaFailure.NotFound, smbFailureOf(apiException(status)))
        }
    }

    @Test
    fun `包一层的运行时异常按原因归类`() {
        assertEquals(MediaFailure.Unreachable, smbFailureOf(SMBRuntimeException(ConnectException("拒绝连接"))))
    }

    @Test
    fun `只有传输失败与 SMBJ 运行时异常算连接丢失`() {
        assertTrue(smbConnectionLost(IOException("读写失败")))
        assertTrue(smbConnectionLost(TransportException("链路断了")))
        assertTrue(smbConnectionLost(SMBRuntimeException("会话已失效")))
        assertTrue(smbConnectionLost(apiException(NtStatus.STATUS_USER_SESSION_DELETED)))
    }

    @Test
    fun `未归类的运行时异常不当成连接丢失`() {
        assertFalse(smbConnectionLost(IllegalStateException("别的错")))
        assertFalse(smbConnectionLost(IllegalArgumentException("参数不对")))
        assertFalse(smbConnectionLost(apiException(NtStatus.STATUS_NOT_SUPPORTED)))
    }

    @Test
    fun `其它错误归为未知`() {
        assertEquals(MediaFailure.Unknown, smbFailureOf(IllegalStateException("别的错")))
        assertEquals(MediaFailure.Unknown, smbFailureOf(apiException(NtStatus.STATUS_NOT_SUPPORTED)))
    }

    private fun apiException(status: NtStatus): SMBApiException =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, null)
}
