package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavSourceVerifierTest {

    private val transport = FakeWebDavTransport()
    private val verifier = WebDavSourceVerifier(transport)

    private val root = "https://dav.example.com/remote.php/dav/files/cc"

    @Test
    fun `校验用给定凭据发一次列目录请求，地址先规范化`() = runTest {
        verifier.verify(url = "  $root/  ", username = "cc", password = "secret")

        assertEquals(listOf("cc" to "secret"), transport.logins)
        assertEquals(listOf(root), transport.listings)
    }

    @Test
    fun `地址不合法时抛出来源失败原因且不请求服务器`() = runTest {
        val exception = runCatching { verifier.verify(url = "dav.example.com/dav", username = "cc", password = "secret") }
            .exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
        assertEquals(emptyList<String>(), transport.listings)
    }

    @Test
    fun `服务器拒绝登录时抛出来源失败原因`() = runTest {
        transport.failure = MediaSourceException(MediaFailure.Authentication, "拒绝登录")

        val exception = runCatching { verifier.verify(url = root, username = "cc", password = "错的") }.exceptionOrNull()

        assertEquals(MediaFailure.Authentication, (exception as MediaSourceException).failure)
    }
}
