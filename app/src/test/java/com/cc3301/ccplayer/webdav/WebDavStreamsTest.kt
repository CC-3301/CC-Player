package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.BookmarkStore
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.streaming.ByteSource
import com.cc3301.ccplayer.streaming.LoopbackStreamServer
import com.cc3301.ccplayer.streaming.httpRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书签查询可被卡住的内存存储：用来在入口解析书签期间插入一次作废。 */
private class GatedBookmarkStore : BookmarkStore {

    private val state = MutableStateFlow<List<NetworkBookmark>>(emptyList())

    val entered = CountDownLatch(1)

    val release = CountDownLatch(1)

    /** 置真时下一次读书签会卡到 [release] 放行为止。 */
    @Volatile
    var gate = false

    override val bookmarks: Flow<List<NetworkBookmark>> = flow {
        if (gate) {
            gate = false
            entered.countDown()
            release.await()
        }
        emitAll(state)
    }

    override suspend fun save(bookmarks: List<NetworkBookmark>) {
        state.value = bookmarks
    }
}

/** 一次准备加一次试连：测试只关心试连结果时用。 */
private suspend fun WebDavStreams.probeForTest(location: String) {
    prepare(location).probe(location)
}

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

        val exception = runCatching { streams.probeForTest(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.Unreachable, (exception as MediaSourceException).failure)
    }

    @Test
    fun `起播前试连能发现不属于任何书签的位置`() = runTest {
        val exception = runCatching { streams.probeForTest(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `探测打开的句柄交给播放的第一个请求复用`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)

        streams.probeForTest(movie)
        assertArrayEquals(payload, httpRequest(streams.urlFor(movie)).body)

        assertEquals("探测不再产生一次被丢弃的连接", 1, transport.openedSizes.size)
    }

    @Test
    fun `已有可用源时探测不再另存句柄`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        httpRequest(streams.urlFor(movie))

        streams.probeForTest(movie)

        assertEquals("播放会直接复用现成的源", 1, transport.openedSizes.size)
    }

    @Test
    fun `丢弃只打标记，句柄留到取用时才关`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        streams.probeForTest(movie)
        val prepared = transport.handles.getValue(movie)

        streams.discardByteSources()

        assertTrue("丢弃路径不碰 I/O", !prepared.closed)
        httpRequest(streams.urlFor(movie))
        awaitClosed(prepared)
        assertEquals(2, transport.openedSizes.size)
    }

    @Test
    fun `归还后关掉已就绪的源，下一次请求重新建立`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        httpRequest(streams.urlFor(movie))

        streams.returnSource(movie)

        awaitClosed(transport.handles.getValue(movie))
        httpRequest(streams.urlFor(movie))
        assertEquals(2, transport.openedSizes.size)
    }

    @Test
    fun `取消打开丢掉探测预置的句柄`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        val owner = streams.prepare(movie)
        owner.probe(movie)
        val prepared = transport.handles.getValue(movie)

        owner.discard()

        awaitClosed(prepared)
        assertEquals("关闭只在关源线程执行", "loopback-source-close", prepared.closedOn)
    }

    @Test
    fun `服务关闭后丢弃预置句柄，关闭落到一次性关源线程`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        val server = LoopbackStreamServer()
        val closeable = WebDavStreams(transport, WebDavLocator(sources), server = server)
        try {
            val owner = closeable.prepare(movie)
            owner.probe(movie)
            val prepared = transport.handles.getValue(movie)

            // 服务已关闭，丢弃走的是真实的派发路径。
            server.close()
            owner.discard()

            awaitClosed(prepared)
            assertEquals("关闭只在关源线程执行", "loopback-source-close-final", prepared.closedOn)
        } finally {
            closeable.close()
        }
    }

    @Test
    fun `探测回来时这次准备已作废，句柄直接丢掉不外借`() = runBlocking {
        addSource(root)
        transport.addFile(movie, payload)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        transport.openGate = {
            entered.countDown()
            gate.await()
        }
        val owner = streams.prepare(movie)
        val probing = thread { runBlocking { owner.probe(movie) } }
        assertTrue("打开没有开始", entered.await(5, TimeUnit.SECONDS))
        // 打开还在途：这次准备作废。
        owner.discard()
        gate.countDown()
        probing.join(5_000)

        val returned = transport.handles.values.single()
        awaitClosed(returned)
        httpRequest(streams.urlFor(movie))
        assertEquals("作废的句柄不外借，播放重新打开", 2, transport.openedSizes.size)
    }

    @Test
    fun `作废与播放取用并发时互不阻塞，句柄最终都被关`() = runBlocking {
        addSource(root)
        transport.addFile(movie, payload)
        repeat(500) {
            val owner = streams.prepare(movie)
            owner.probe(movie)
            val barrier = CyclicBarrier(2)
            val discarding = Thread {
                barrier.await()
                owner.discard()
            }.apply { isDaemon = true }
            val taking = Thread {
                barrier.await()
                httpRequest(streams.urlFor(movie))
            }.apply { isDaemon = true }
            discarding.start()
            taking.start()
            // 两条线程必须在时限内各自收工：任一方等另一方持有的锁就会超时。
            discarding.join(5_000)
            taking.join(5_000)
            assertFalse("作废那条线程被卡住", discarding.isAlive)
            assertFalse("取用那条线程被卡住", taking.isAlive)
        }

        streams.returnSource(movie)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && transport.handles.values.any { !it.closed }) {
            Thread.sleep(10)
        }
        assertTrue("句柄最终都要关掉", transport.handles.values.all { it.closed })
    }

    @Test
    fun `打开回来与作废并发时每条句柄恰好关一次`() = runBlocking {
        addSource(root)
        transport.addFile(movie, payload)
        val server = LoopbackStreamServer()
        val racy = WebDavStreams(transport, WebDavLocator(sources), server = server)
        val rounds = 4_000
        try {
            // 两条常驻线程逐轮对齐起跑：作废与探测打开回来交错，覆盖「登记前后被作废」的窗口。
            val start = CyclicBarrier(2)
            val probed = CyclicBarrier(2)
            val owners = Array(rounds) { racy.prepare(movie) }
            val probing = Thread {
                for (owner in owners) {
                    start.await()
                    runBlocking { owner.probe(movie) }
                    probed.await()
                }
            }.apply { isDaemon = true }
            val discarding = Thread {
                for (owner in owners) {
                    start.await()
                    owner.discard()
                    probed.await()
                }
            }.apply { isDaemon = true }
            probing.start()
            discarding.start()
            probing.join(60_000)
            discarding.join(60_000)
            assertFalse("探测那条线程被卡住", probing.isAlive)
            assertFalse("作废那条线程被卡住", discarding.isAlive)

            // 关源串行：探针排在后面处理完，说明之前派发的关闭都已经执行。
            drainCloser(server)
            assertTrue(
                "每条句柄恰好关一次",
                transport.allHandles.all { it.closeCount == 1 },
            )
        } finally {
            racy.close()
        }
    }

    @Test
    fun `同一实例上前一次准备作废不关后一次探好的句柄`() = runBlocking {
        addSource(root)
        transport.addFile(movie, payload)
        val server = LoopbackStreamServer()
        val shared = WebDavStreams(transport, WebDavLocator(sources), server = server)
        try {
            val first = shared.prepare(movie)
            first.probe(movie)
            val firstHandle = transport.handles.getValue(movie)
            val second = shared.prepare(movie)
            second.probe(movie)
            val secondHandle = transport.handles.getValue(movie)
            awaitClosed(firstHandle)

            first.discard()
            // 关源串行：排在这次作废之后的探针被关掉，说明它前面的派发都已经处理完。
            drainCloser(server)

            assertFalse("后一次探好的句柄被前一次作废关掉了", secondHandle.closed)
            httpRequest(shared.urlFor(movie))
            assertEquals("探测结果仍交给播放复用", 2, transport.openedSizes.size)
            shared.returnSource(movie)
            awaitClosed(secondHandle)
        } finally {
            shared.close()
        }
    }

    @Test
    fun `入口解析书签期间被作废，迟到回来的句柄不外借`() = runBlocking {
        val store = GatedBookmarkStore()
        val gatedSources = NetworkSourceRepository(store, FakeCredentialStore())
        val gated = WebDavStreams(transport, WebDavLocator(gatedSources))
        try {
            gatedSources.add(NetworkBookmark.fromUrl(root, "cc")!!, password = "secret")
            transport.addFile(movie, payload)
            store.gate = true
            val owner = gated.prepare(movie)
            val probing = thread { runBlocking { owner.probe(movie) } }
            assertTrue("书签查询没有开始", store.entered.await(5, TimeUnit.SECONDS))

            // 书签还没解析出来这次准备就作废了：恢复后打开的句柄不能移交给后一次打开。
            owner.discard()
            store.release.countDown()
            probing.join(5_000)

            val opened = transport.handles.values.last()
            awaitClosed(opened)
            httpRequest(gated.urlFor(movie))
            assertEquals("作废的句柄不外借，播放重新打开", 2, transport.openedSizes.size)
        } finally {
            gated.close()
        }
    }

    /**
     * 等关源线程把发出去的关闭都处理完：关源串行，探针位置排在后面即代表前面都做完。
     * 探针本身也要关，用一条独立的字节源。
     */
    private fun drainCloser(target: LoopbackStreamServer) {
        val done = CountDownLatch(1)
        val probe = object : ByteSource {
            override val size: Long = 1L

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = 0

            override fun close() {
                done.countDown()
            }
        }
        target.closeInBackground(probe)
        assertTrue("关源线程没有处理完", done.await(5, TimeUnit.SECONDS))
    }

    /** 关源在关源线程上做：等到句柄真的关上再断言。 */
    private fun awaitClosed(handle: RecordingWebDavHandle, timeoutMillis: Long = 2_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (handle.closed) return
            Thread.sleep(10)
        }
        assertTrue("句柄没有关闭", handle.closed)
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
