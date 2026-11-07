package com.cc3301.ccplayer.webdav

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

class WebDavStreamsTest {

    private val transport = FakeWebDavTransport()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val streams = WebDavStreams(transport, WebDavLocator(sources))

    private val root = "https://dav.example.com/remote.php/dav/files/cc"
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
    fun `把 WebDAV 文件摊成本机地址，播放器拿到的是回环地址`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)

        val url = streams.urlFor(movie)

        assertTrue(url.startsWith("http://127.0.0.1:"))
        assertArrayEquals(payload, httpRequest(url).body)
    }

    @Test
    fun `本机地址支持 Range 请求`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)

        val response = httpRequest(streams.urlFor(movie), range = "bytes=100-199")

        assertEquals(206, response.statusCode)
        assertArrayEquals(payload.copyOfRange(100, 200), response.body)
    }

    @Test
    fun `同一个位置恒得同一个地址`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)

        assertEquals(streams.urlFor(movie), streams.urlFor(movie))
    }

    @Test
    fun `起播前试连能发现连不上的服务器`() = runTest {
        addSource(root)
        transport.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        val exception = runCatching { streams.probe(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.Unreachable, (exception as MediaSourceException).failure)
    }

    @Test
    fun `起播前试连能发现不属于任何书签的位置`() = runTest {
        val exception = runCatching { streams.probe(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `探测打开的句柄交给播放的第一个请求复用`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)

        streams.probe(movie)
        assertArrayEquals(payload, httpRequest(streams.urlFor(movie)).body)

        assertEquals("探测不再产生一次被丢弃的连接", 1, transport.openedSizes.size)
    }

    @Test
    fun `已有可用源时探测不再另存句柄`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        httpRequest(streams.urlFor(movie))

        streams.probe(movie)

        assertEquals("播放会直接复用现成的源", 1, transport.openedSizes.size)
    }

    @Test
    fun `丢弃只打标记，句柄留到取用时才关`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        streams.probe(movie)
        val prepared = transport.handles.getValue(movie)

        streams.discardByteSources()

        assertTrue("丢弃路径不碰 I/O", !prepared.closed)
        httpRequest(streams.urlFor(movie))
        assertTrue("取用时关掉留下的句柄", prepared.closed)
        assertEquals(2, transport.openedSizes.size)
    }

    @Test
    fun `停播关闭字节源后下一次请求重新建立`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        httpRequest(streams.urlFor(movie))

        streams.closeByteSources()

        assertTrue(transport.handles.getValue(movie).closed)
        httpRequest(streams.urlFor(movie))
        assertEquals(2, transport.openedSizes.size)
    }

    @Test
    fun `关闭后已建立的读句柄一并关闭`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        httpRequest(streams.urlFor(movie))

        streams.close()

        assertEquals(true, transport.handles.getValue(movie).closed)
    }
}
