package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.streaming.httpRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbStreamsTest {

    private val connector = FakeSmbConnector()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val streams = SmbStreams(connector, SmbLocator(sources))

    private val root = "smb://nas/media"
    private val movie = "$root/电影.mkv"
    private val payload = ByteArray(4096) { (it % 241).toByte() }

    @After
    fun tearDown() {
        streams.close()
    }

    @Test
    fun `把 SMB 文件摊成本机地址，播放器拿到的是回环地址`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        connector.addFile("media", "电影.mkv", payload)

        val url = streams.urlFor(movie)

        assertEquals(true, url!!.startsWith("http://127.0.0.1:"))
        assertArrayEquals(payload, httpRequest(url).body)
    }

    @Test
    fun `本机地址支持 Range 请求`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        connector.addFile("media", "电影.mkv", payload)

        val response = httpRequest(streams.urlFor(movie)!!, range = "bytes=100-199")

        assertEquals(206, response.statusCode)
        assertArrayEquals(payload.copyOfRange(100, 200), response.body)
    }

    @Test
    fun `书签以外的位置没有本机地址`() = runTest {
        assertNull(streams.urlFor(movie))
    }

    @Test
    fun `目录位置没有本机地址`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")

        assertNull(streams.urlFor(root))
    }

    @Test
    fun `关闭后读句柄一并关闭`() = runTest {
        sources.add(url = root, username = "cc", password = "secret")
        connector.addFile("media", "电影.mkv", payload)
        streams.urlFor(movie)

        streams.close()

        assertEquals(true, connector.handles.getValue("media/电影.mkv").closed)
    }
}
