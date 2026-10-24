package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.streaming.httpRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private suspend fun addSource(url: String, username: String = "cc") {
        sources.add(NetworkBookmark.fromUrl(url, username)!!, password = "secret")
    }

    @Test
    fun `把 SMB 文件摊成本机地址，播放器拿到的是回环地址`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        val url = streams.urlFor(movie)

        assertTrue(url.startsWith("http://127.0.0.1:"))
        assertArrayEquals(payload, httpRequest(url).body)
    }

    @Test
    fun `本机地址支持 Range 请求`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        val response = httpRequest(streams.urlFor(movie), range = "bytes=100-199")

        assertEquals(206, response.statusCode)
        assertArrayEquals(payload.copyOfRange(100, 200), response.body)
    }

    @Test
    fun `同一个位置恒得同一个地址`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        assertEquals(streams.urlFor(movie), streams.urlFor(movie))
    }

    @Test
    fun `起播前试连能发现连不上的共享`() = runTest {
        addSource(root)
        connector.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        val exception = runCatching { streams.probe(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.Unreachable, (exception as MediaSourceException).failure)
    }

    @Test
    fun `起播前试连能发现不属于任何书签的位置`() = runTest {
        val exception = runCatching { streams.probe(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `关闭后已建立的读句柄一并关闭`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        httpRequest(streams.urlFor(movie))

        streams.close()

        assertEquals(true, connector.handles.getValue("media/电影.mkv").closed)
        // 先关连接让阻塞读退出，再尽力关句柄。
        assertEquals(listOf("connection", "handle"), connector.closeOrder)
    }
}
