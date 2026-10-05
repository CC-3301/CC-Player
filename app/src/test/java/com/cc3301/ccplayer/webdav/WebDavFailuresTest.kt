package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class WebDavFailuresTest {

    @Test
    fun `连不上主机归为不可达`() {
        listOf(
            UnknownHostException("dav.example.com"),
            ConnectException("拒绝连接"),
            SocketTimeoutException("超时"),
            SSLHandshakeException("证书不对"),
            IOException("读写失败"),
        ).forEach { failure ->
            assertEquals(failure.toString(), MediaFailure.Unreachable, webdavFailureOf(failure))
        }
    }

    @Test
    fun `其它错误归为未知`() {
        assertEquals(MediaFailure.Unknown, webdavFailureOf(IllegalStateException("别的错")))
    }

    @Test
    fun `状态码归到来源失败原因`() {
        assertEquals(MediaFailure.Authentication, webdavStatusFailure(401))
        assertEquals(MediaFailure.Authentication, webdavStatusFailure(403))
        assertEquals(MediaFailure.NotFound, webdavStatusFailure(404))
        assertEquals(MediaFailure.Unknown, webdavStatusFailure(500))
        assertEquals(MediaFailure.Unknown, webdavStatusFailure(200))
    }
}
