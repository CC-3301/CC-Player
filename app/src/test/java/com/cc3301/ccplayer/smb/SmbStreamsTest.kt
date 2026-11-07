package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.streaming.httpRequest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一次准备加一次试连：测试只关心试连结果时用。 */
private suspend fun SmbStreams.probeForTest(location: String) {
    prepare(location).probe(location)
}

class SmbStreamsTest {

    private val connector = FakeSmbConnector()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val sessions = SmbSessions(connector)
    private val streams = SmbStreams(sessions, SmbLocator(sources))

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

        val exception = runCatching { streams.probeForTest(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.Unreachable, (exception as MediaSourceException).failure)
    }

    @Test
    fun `起播前试连能发现不属于任何书签的位置`() = runTest {
        val exception = runCatching { streams.probeForTest(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `探测与播放共用一次握手`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        streams.probeForTest(movie)
        assertArrayEquals(payload, httpRequest(streams.urlFor(movie)).body)

        assertEquals("会话建立只发生一次", 1, connector.shares.size)
    }

    @Test
    fun `停播关闭字节源后会话引用归零并能闲置回收`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector, scope = this, idleMillis = 1_000)
        val stopsWithSources = SmbStreams(sessions, SmbLocator(sources))
        val url = stopsWithSources.urlFor(movie)

        httpRequest(url)
        stopsWithSources.returnSource(movie)
        awaitClosed(connector.handles.getValue("media/电影.mkv"))
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals("停播后闲置回收能关掉会话", listOf("media"), connector.closedShares)
        stopsWithSources.close()
    }

    /** 关源在关源线程上做：等到句柄真的关上再断言。 */
    private fun awaitClosed(handle: RecordingSmbHandle, timeoutMillis: Long = 2_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (handle.closed) return
            Thread.sleep(10)
        }
        assertTrue("句柄没有关闭", handle.closed)
    }

    @Test
    fun `关闭代理后读句柄关闭但会话留给下次取用`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        httpRequest(streams.urlFor(movie))

        streams.close()

        assertEquals(true, connector.handles.getValue("media/电影.mkv").closed)
        assertTrue("会话归应用级会话层管，不随代理关闭", connector.closedShares.isEmpty())
    }
}
