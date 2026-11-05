package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.streaming.ByteSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkPreviewExtractorTest {

    private val payload = ByteArray(64) { it.toByte() }
    private val reads = RecordingReads(payload)
    private val movie = MediaFile(
        name = "电影.mkv",
        location = "smb://nas/media/电影.mkv",
        lastModified = 1_700_000_000_000,
        source = MediaSource.Network,
    )

    private fun extractor(decoder: NetworkPreviewDecoder) = NetworkPreviewExtractor(reads, decoder = decoder)

    @Test
    fun `网络取图成功时返回解码结果并关闭句柄`() = runTest {
        var askedKind: MediaKind? = null
        val extractor = extractor(
            NetworkPreviewDecoder { opened, kind, _ ->
                askedKind = kind
                ByteArray(8).also { buffer -> opened.readAt(0, buffer, 0, buffer.size) }
            },
        )

        assertArrayEquals(payload.copyOfRange(0, 8), extractor.imageBytes(movie, NETWORK_TARGET_WIDTH)!!)
        assertEquals(MediaKind.Video, askedKind)
        assertEquals("解码与预读各开一条句柄", 2, reads.opened.size)
        assertEquals("文件大小取自目录列表", setOf(movie.size), reads.sizes.toSet())
        assertTrue("句柄全部关闭", reads.sources.all { source -> source.closed })
    }

    @Test
    fun `预读用独立句柄，解码读本地字节`() = runTest {
        val executor = StepExecutor()
        val extractor = NetworkPreviewExtractor(
            reads,
            decoder = NetworkPreviewDecoder { opened, _, _ ->
                val buffer = ByteArray(8)
                opened.readAt(0, buffer, 0, buffer.size)
                ByteArray(8)
            },
            readAhead = executor,
        )

        val prepared = extractor.prepare(movie)!!
        try {
            executor.runAll()
            assertArrayEquals(ByteArray(8), prepared.decode(NETWORK_TARGET_WIDTH))
        } finally {
            prepared.close()
        }

        assertEquals("解码与预读各开一条句柄", 2, reads.opened.size)
        assertTrue("解码只吃预读拉进来的本地字节", reads.sources[0].reads.isEmpty())
        assertEquals("预读取文件头", listOf(0L), reads.sources[1].reads)
    }

    @Test
    fun `等播放读时长单独记一行`() = runTest {
        val lines = mutableListOf<String>()
        val diagnostics = object : DiagnosticsLog {
            override val enabled = true

            override fun log(message: String) {
                lines += message
            }
        }
        val executor = StepExecutor()
        val extractor = NetworkPreviewExtractor(
            reads,
            decoder = NetworkPreviewDecoder { opened, _, _ ->
                val buffer = ByteArray(8)
                opened.readAt(0, buffer, 0, buffer.size)
                ByteArray(8)
            },
            readAhead = executor,
            diagnostics = diagnostics,
        )

        val prepared = extractor.prepare(movie)!!
        executor.runAll()
        prepared.decode(NETWORK_TARGET_WIDTH)
        prepared.close()

        assertTrue("应记等播放读：$lines", lines.any { it.startsWith("网络封面: 电影.mkv 等播放读 ") })
    }

    @Test
    fun `网络取图使用调用方的目标宽度`() = runTest {
        var askedWidth: Int? = null
        val extractor = extractor(
            NetworkPreviewDecoder { _, _, width ->
                askedWidth = width
                ByteArray(1)
            },
        )

        extractor.imageBytes(movie, targetWidth = 320)

        assertEquals(320, askedWidth)
    }

    @Test
    fun `解码与关闭句柄在调用方之外的线程执行`() = runTest {
        val callerThread = Thread.currentThread()
        var decodeThread: Thread? = null
        val extractor = NetworkPreviewExtractor(
            reads,
            decoder = NetworkPreviewDecoder { _, _, _ ->
                decodeThread = Thread.currentThread()
                ByteArray(1)
            },
            readAhead = StepExecutor(),
        )

        extractor.imageBytes(movie, NETWORK_TARGET_WIDTH)

        assertNotNull("解码应当执行", decodeThread)
        assertTrue("解码不得在调用方线程执行", callerThread !== decodeThread)
        assertEquals("解码与预读各开一条句柄", 2, reads.sources.size)
        assertTrue("两条句柄都应关闭", reads.sources.all { source -> source.closed })
        assertTrue(
            "关闭句柄不得在调用方线程执行",
            reads.sources.all { source -> source.closedOn != null && source.closedOn !== callerThread },
        )
    }

    @Test
    fun `取消后仍执行关闭且不在调用方线程`() = runBlocking {
        val callerThread = Thread.currentThread()
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val extractor = NetworkPreviewExtractor(
            reads,
            decoder = NetworkPreviewDecoder { _, _, _ ->
                decoding.countDown()
                release.await(5, TimeUnit.SECONDS)
                ByteArray(1)
            },
            readAhead = StepExecutor(),
        )

        val job = launch(Dispatchers.Default) { extractor.imageBytes(movie, NETWORK_TARGET_WIDTH) }
        assertTrue("解码应当开始", decoding.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        withTimeout(5_000) { job.join() }

        assertEquals("解码与预读各开一条句柄", 2, reads.sources.size)
        assertTrue("取消后两条句柄都要关闭", reads.sources.all { source -> source.closed })
        assertTrue(
            "取消后的关闭不得在调用方线程执行",
            reads.sources.all { source -> source.closedOn != null && source.closedOn !== callerThread },
        )
    }

    @Test
    fun `音频按内嵌封面取图`() = runTest {
        var askedKind: MediaKind? = null
        val extractor = extractor(
            NetworkPreviewDecoder { _, kind, _ ->
                askedKind = kind
                ByteArray(2)
            },
        )

        extractor.imageBytes(movie.copy(name = "歌.mp3"), NETWORK_TARGET_WIDTH)

        assertEquals(MediaKind.Audio, askedKind)
        assertTrue(reads.sources.all { source -> source.closed })
    }

    @Test
    fun `解码抛异常时向调用方抛出并关闭句柄`() = runTest {
        val extractor = extractor(NetworkPreviewDecoder { _, _, _ -> error("解码失败") })

        try {
            extractor.imageBytes(movie, NETWORK_TARGET_WIDTH)
            fail("应抛出解码异常")
        } catch (expected: IllegalStateException) {
            // 预期失败路径。
        }
        assertTrue(reads.sources.all { source -> source.closed })
    }

    @Test
    fun `预读句柄打开被取消时关闭已经打开的句柄`() = runTest {
        var primaryClosed = false
        var opens = 0
        val reads = object : NetworkMediaReads {
            override suspend fun openRead(location: String, size: Long): ByteSource {
                opens++
                if (opens == 1) {
                    return object : ByteSource {
                        override val size: Long = payload.size.toLong()

                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = 0

                        override fun close() {
                            primaryClosed = true
                        }
                    }
                }
                throw CancellationException("取消")
            }
        }
        val extractor = NetworkPreviewExtractor(
            reads,
            decoder = NetworkPreviewDecoder { _, _, _ -> ByteArray(1) },
        )

        try {
            extractor.prepare(movie)
            fail("应向上抛出取消")
        } catch (expected: CancellationException) {
            // 预期路径。
        }
        assertTrue("已打开的第一条句柄必须关闭", primaryClosed)
    }

    @Test
    fun `准备失败按失败分类收尾：本地失败只关句柄，会话失败作废`() = runTest {
        // 预读池拒单是本地调度失败，会话没坏：只关句柄。
        val localReads = RecordingReads(ByteArray(1 shl 20))
        val localExecutor = RejectingExecutor(failAt = 2)
        val local = NetworkPreviewExtractor(
            localReads,
            decoder = NetworkPreviewDecoder { _, _, _ -> ByteArray(1) },
            readAhead = localExecutor,
        )

        try {
            local.prepare(movie)
            fail("应向上抛出预读提交失败")
        } catch (expected: RejectedExecutionException) {
            // 预期路径。
        }

        assertTrue("两条句柄都要关闭", localReads.sources.all { source -> source.closed })
        assertFalse("本地失败不作废会话", localReads.sources.any { source -> source.discarded })
        val readsBefore = localReads.sources.sumOf { source -> source.reads.size }
        localExecutor.runAll()
        assertEquals(
            "关闭后已排队的预读不再读取",
            readsBefore,
            localReads.sources.sumOf { source -> source.reads.size },
        )

        // 会话类失败：作废已打开的句柄，下一次准备换新会话。
        val lostReads = RecordingReads(ByteArray(1 shl 20))
        val lost = NetworkPreviewExtractor(
            lostReads,
            decoder = NetworkPreviewDecoder { _, _, _ -> ByteArray(1) },
            readAhead = RejectingExecutor(
                failAt = 2,
                failure = MediaSourceException(MediaFailure.Unreachable, "连接已断"),
            ),
        )

        try {
            lost.prepare(movie)
            fail("应向上抛出会话失败")
        } catch (expected: MediaSourceException) {
            // 预期路径。
        }

        assertTrue("会话失败要作废已打开的句柄", lostReads.sources.all { source -> source.discarded })
    }

    @Test
    fun `打开句柄失败时向调用方抛出`() = runTest {
        val extractor = NetworkPreviewExtractor(
            { _, _ -> error("打不开") },
            decoder = NetworkPreviewDecoder { _, _, _ -> ByteArray(1) },
        )

        try {
            extractor.imageBytes(movie, NETWORK_TARGET_WIDTH)
            fail("应抛出打开失败")
        } catch (expected: IllegalStateException) {
            // 预期失败路径。
        }
    }

    @Test
    fun `非媒体文件不打开句柄`() = runTest {
        val extractor = extractor(NetworkPreviewDecoder { _, _, _ -> ByteArray(1) })

        assertNull(extractor.imageBytes(movie.copy(name = "说明.txt"), NETWORK_TARGET_WIDTH))
        assertTrue(reads.opened.isEmpty())
    }

    @Test
    fun `取消时关闭句柄以中断阻塞读取`() = runBlocking {
        val blocking = BlockingByteSource()
        val extractor = NetworkPreviewExtractor(
            { _, _ -> blocking },
            decoder = NetworkPreviewDecoder { opened, _, _ ->
                opened.readAt(0, ByteArray(1), 0, 1)
                ByteArray(1)
            },
        )

        val job = launch(Dispatchers.Default) { extractor.imageBytes(movie, NETWORK_TARGET_WIDTH) }
        assertTrue(blocking.reading.await(5, TimeUnit.SECONDS))

        job.cancel()
        withTimeout(5_000) { job.join() }

        assertTrue(blocking.closed)
    }

    @Test
    fun `超时取消打断传输读时作废会话，下一次准备新建连`() = runBlocking {
        val reads = SessionReads(blockFirstRead = true)
        val extractor = NetworkPreviewExtractor(
            reads,
            decoder = NetworkPreviewDecoder { opened, _, _ ->
                opened.readAt(0, ByteArray(1), 0, 1)
                ByteArray(1)
            },
            readAhead = StepExecutor(),
        )
        val prepared = extractor.prepare(movie)!!

        // 解码阻塞在传输读里，等它真正开始读再按超时取消。
        val decoding = launch(Dispatchers.Default) { prepared.decode(NETWORK_TARGET_WIDTH) }
        assertTrue("传输读应当已发起", reads.readStarted.await(5, TimeUnit.SECONDS))
        decoding.cancelAndJoin()
        prepared.discard()

        assertEquals("打断传输读要作废会话", 1, reads.discardedSessions)

        extractor.prepare(movie)!!.close()
        assertEquals("下一次准备要重新建连", 2, reads.connections)
    }

    @Test
    fun `没有在途传输读的放弃不作废会话`() = runTest {
        val reads = SessionReads()
        val extractor = NetworkPreviewExtractor(
            reads,
            decoder = NetworkPreviewDecoder { _, _, _ -> ByteArray(1) },
            readAhead = StepExecutor(),
        )
        val prepared = extractor.prepare(movie)!!
        prepared.discard()

        assertEquals("没有在途读不作废会话", 0, reads.discardedSessions)
        extractor.prepare(movie)!!.close()
        assertEquals("下一次准备复用会话", 1, reads.connections)
    }

    @Test
    fun `读取位置越界与末尾返回 -1`() {
        val source = RecordingByteSource(payload)
        val buffer = ByteArray(8)

        assertEquals(-1, rangeReadAt(source, -1, buffer, 0, buffer.size))
        assertEquals(-1, rangeReadAt(source, payload.size.toLong(), buffer, 0, buffer.size))
        assertEquals(-1, rangeReadAt(source, payload.size + 10L, buffer, 0, buffer.size))
        assertTrue(source.reads.isEmpty())
    }

    @Test
    fun `短读按实际字节数返回`() {
        val source = RecordingByteSource(payload)
        val buffer = ByteArray(16)

        assertEquals(4, rangeReadAt(source, payload.size - 4L, buffer, 0, buffer.size))
        assertArrayEquals(payload.copyOfRange(payload.size - 4, payload.size), buffer.copyOfRange(0, 4))
    }

    @Test
    fun `底层读不到字节按末尾处理`() {
        val empty = RecordingByteSource(ByteArray(0))

        assertEquals(-1, rangeReadAt(empty, 0, ByteArray(4), 0, 4))
    }
}

/** 按顺序执行预读任务，让预读在测试线程上确定发生；忽略 rank 与字节源。 */
private class StepExecutor : PreviewReadAhead {

    private val tasks = ArrayDeque<Runnable>()

    override fun submit(rank: Long, key: Any, task: Runnable) {
        tasks.addLast(task)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

/** 前 [failAt] - 1 个任务入队，第 [failAt] 个提交时拒绝；用来构造窗口已建、准备未交付。 */
private class RejectingExecutor(
    private val failAt: Int,
    private val failure: Throwable = RejectedExecutionException("预读线程拒绝任务"),
) : PreviewReadAhead {

    private val tasks = ArrayDeque<Runnable>()
    private var submissions = 0

    override fun submit(rank: Long, key: Any, task: Runnable) {
        submissions++
        if (submissions >= failAt) throw failure
        tasks.addLast(task)
    }

    /** 跑完已入队的任务：窗口关闭后它们必须直接退出，不再读底层。 */
    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

/** 记录读取位置与关闭状态的假随机读句柄。 */
private class RecordingByteSource(private val bytes: ByteArray) : ByteSource {

    var closed = false

    /** 是否走了作废路径：作废要连会话一起丢弃，不能只关句柄。 */
    var discarded = false

    /** 关闭发生的线程：句柄关闭不得落在调用方线程。 */
    var closedOn: Thread? = null
    val reads = mutableListOf<Long>()

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        reads += position
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closed = true
        closedOn = Thread.currentThread()
    }

    override fun discard() {
        discarded = true
        close()
    }
}

/** 读取会一直阻塞到句柄关闭，用来验证取消落到传输层。 */
private class BlockingByteSource : ByteSource {

    private val lock = Object()
    private var isClosed = false

    val reading = CountDownLatch(1)

    val closed: Boolean get() = synchronized(lock) { isClosed }

    override val size: Long get() = 64

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        reading.countDown()
        synchronized(lock) {
            while (!isClosed) lock.wait()
        }
        return 0
    }

    override fun close() {
        synchronized(lock) {
            isClosed = true
            lock.notifyAll()
        }
    }
}

/** 记录会话建连与作废的假读取；可让第一次读取阻塞到句柄关闭，模拟传输读被打断。 */
private class SessionReads(private val blockFirstRead: Boolean = false) : NetworkMediaReads {

    private val lock = Object()
    private var live = false
    private var firstRead = true

    var connections = 0
    var discardedSessions = 0
    val readStarted = CountDownLatch(1)

    override suspend fun openRead(location: String, size: Long): ByteSource {
        synchronized(lock) {
            if (!live) {
                live = true
                connections++
            }
        }
        return SessionSource()
    }

    private inner class SessionSource : ByteSource {
        private val closed = AtomicBoolean(false)

        override val size: Long = 64

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            val blocking = synchronized(lock) {
                val first = blockFirstRead && firstRead
                if (first) firstRead = false
                first
            }
            if (blocking) {
                readStarted.countDown()
                synchronized(lock) {
                    while (!closed.get()) lock.wait()
                }
                throw IOException("读取已取消")
            }
            if (position >= size) return 0
            buffer[offset] = 7
            return 1
        }

        override fun close() {
            closed.set(true)
            synchronized(lock) { lock.notifyAll() }
        }

        override fun discard() {
            synchronized(lock) {
                if (live) {
                    live = false
                    discardedSessions++
                }
            }
            close()
        }
    }
}

/** 每次打开都给一条新的句柄，记录打开位置与文件大小。 */
private class RecordingReads(private val payload: ByteArray) : NetworkMediaReads {

    val opened = mutableListOf<String>()
    val sizes = mutableListOf<Long>()
    val sources = mutableListOf<RecordingByteSource>()

    override suspend fun openRead(location: String, size: Long): ByteSource {
        opened += location
        sizes += size
        return RecordingByteSource(payload).also { sources += it }
    }
}
