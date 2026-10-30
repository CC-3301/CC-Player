package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PreviewByteWindowTest {

    private val chunk = 64

    private val window = 256L

    @Test
    fun `解码按字节位置读到与底层一致的内容`() {
        val payload = payload(1000)
        val origin = RecordingOrigin(payload)
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = QueueExecutor())

        assertArrayEquals(payload.copyOfRange(0, 10), read(source, 0, 10))
        assertArrayEquals(payload.copyOfRange(70, 120), read(source, 70, 50))
        assertArrayEquals(payload.copyOfRange(990, 1000), read(source, 990, 20))
    }

    @Test
    fun `同一块只向底层读一次`() {
        val origin = RecordingOrigin(payload(1000))
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = QueueExecutor())

        read(source, 0, 4)
        read(source, 10, 4)
        read(source, 63, 4)

        assertEquals(1, origin.reads.size)
        assertEquals(0L to chunk, origin.reads.single())
    }

    @Test
    fun `命中后向后扩两块，后续读取不再请求底层`() {
        val origin = RecordingOrigin(payload(1000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = executor)

        read(source, 0, 4)
        executor.runAll()

        val before = origin.reads.size
        read(source, 100, 8)
        read(source, 150, 8)
        assertEquals(before, origin.reads.size)
    }

    @Test
    fun `预读队列有界，一次命中只排两块`() {
        val origin = RecordingOrigin(payload(10_000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = executor)

        read(source, 0, 4)

        assertEquals(2, executor.pending())
        executor.runAll()
        assertEquals(3, origin.reads.size)
    }

    @Test
    fun `元数据阶段只读头 64 KiB 与尾 1 MiB`() {
        val size = 20 * 1024 * 1024
        val origin = RecordingOrigin(payload(size))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, readAheadExecutor = executor)

        source.startMetadataReadAhead()
        executor.runAll()

        assertEquals(0L to PREVIEW_METADATA_HEAD_BYTES, origin.reads.first())
        val tailStart = size - PREVIEW_METADATA_TAIL_BYTES.toLong()
        val tail = origin.reads.drop(1)
        assertEquals(
            "尾部按读取块补齐：$tail",
            (0 until 4).map { tailStart + it * PREVIEW_READ_CHUNK_BYTES.toLong() },
            tail.map { it.first },
        )
        assertTrue("尾部每块不超过读取块上限：$tail", tail.all { it.second == PREVIEW_READ_CHUNK_BYTES })
        val totalBytes = origin.reads.sumOf { it.second }
        assertEquals(PREVIEW_METADATA_HEAD_BYTES + PREVIEW_METADATA_TAIL_BYTES, totalBytes)
    }

    @Test
    fun `小文件的头尾区间相接时一次读完`() {
        val origin = RecordingOrigin(payload(500_000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, readAheadExecutor = executor)

        source.startMetadataReadAhead()
        executor.runAll()

        assertEquals(500_000, origin.reads.sumOf { it.second })
        assertEquals("不重复读同一段：${origin.reads}", origin.reads.map { it.first }.distinct().size, origin.reads.size)
    }

    @Test
    fun `换锚点不丢弃已读到的块`() {
        val origin = RecordingOrigin(payload(10_000))
        val executor = QueueExecutor()
        // 窗口容得下两处锚点附近的块：换锚点只补新块，不丢旧块。
        val source = PreviewByteWindow(origin, windowBytes = 8L * chunk, chunkBytes = chunk, readAheadExecutor = executor)

        read(source, 0, 4)
        executor.runAll()

        read(source, 5000, 4)
        executor.runAll()
        val readsAfterJump = origin.reads.size

        read(source, 0, 4)
        executor.runAll()

        assertEquals("跳读后回来的块仍在本地", readsAfterJump, origin.reads.size)
    }

    @Test
    fun `跳读只补新位置附近的块`() {
        val origin = RecordingOrigin(payload(10_000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = executor)

        read(source, 0, 4)
        executor.runAll()
        val beforeJump = origin.reads.size

        read(source, 5000, 4)
        executor.runAll()
        val afterJump = origin.reads.drop(beforeJump)

        assertEquals(4992L to chunk, afterJump.first())
        assertTrue("跳读后不再回读旧位置：$afterJump", afterJump.all { it.first >= 4992 })
    }

    @Test
    fun `常驻块按最近使用淘汰，总量不超过窗口上限`() {
        val origin = RecordingOrigin(payload(10_000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = executor)

        repeat(10) { index ->
            read(source, index * chunk.toLong(), 1)
            executor.runAll()
        }
        val readsAfterScan = origin.reads.size

        read(source, 0, 1)

        assertEquals(readsAfterScan + 1, origin.reads.size)
        assertEquals(0L to chunk, origin.reads.last())
    }

    @Test
    fun `读取块不越过底层协商上限`() {
        val maxRead = 16 * 1024
        val origin = RecordingOrigin(payload(1 shl 20), maxReadBytes = maxRead)
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, readAheadExecutor = executor)

        read(source, 0, 4)
        executor.runAll()
        read(source, 300_000, 8)
        executor.runAll()

        assertTrue("每次读取都不超过协商上限：${origin.reads}", origin.reads.all { it.second <= maxRead })
        assertEquals(maxRead, origin.reads.first().second)
    }

    @Test
    fun `预读走独立字节源`() {
        val decodeOrigin = RecordingOrigin(payload(1000))
        val readAheadOrigin = RecordingOrigin(payload(1000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(
            decodeOrigin,
            windowBytes = window,
            chunkBytes = chunk,
            readAheadSource = readAheadOrigin,
            readAheadExecutor = executor,
        )

        read(source, 0, 4)
        executor.runAll()

        assertEquals("解码只补当前块", listOf(0L to chunk), decodeOrigin.reads)
        assertEquals("预读落在独立句柄", listOf(64L to chunk, 128L to chunk), readAheadOrigin.reads)
        source.close()
        assertTrue(decodeOrigin.closed)
        assertTrue(readAheadOrigin.closed)
    }

    @Test
    fun `预读与解码读取并发，同一时刻不超过两路`() {
        val origin = SlowOrigin(payload(100_000), delayMillis = 200)
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk)

        read(source, 0, 4)
        assertTrue("预读应已开始", origin.awaitReading())
        read(source, 5000, 4)

        assertEquals(2, origin.maxConcurrent)
        source.close()
    }

    @Test
    fun `底层读取失败时向调用方抛出`() {
        val origin = RecordingOrigin(payload(1000)).apply { failure = IOException("连不上") }
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = QueueExecutor())

        try {
            read(source, 0, 4)
            fail("应抛出读取失败")
        } catch (expected: IOException) {
            assertEquals("连不上", expected.message)
        }
    }

    @Test
    fun `关闭时正在补齐的块不返回`() {
        val origin = BlockingOrigin(payload(1000))
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = QueueExecutor())
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val reader = Thread { failure.set(runCatching { read(source, 0, 4) }.exceptionOrNull()) }
        reader.start()
        assertTrue("读应已进入底层", origin.awaitReading())

        source.close()
        origin.release()
        reader.join(TimeUnit.SECONDS.toMillis(5))

        assertTrue("关闭后补齐的块不得返回：${failure.get()}", failure.get() is IOException)
    }

    @Test
    fun `关闭后读取失败并关闭底层`() {
        val origin = RecordingOrigin(payload(1000))
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = QueueExecutor())
        read(source, 0, 4)

        source.close()

        assertTrue(origin.closed)
        try {
            read(source, 0, 4)
            fail("关闭后读取必须失败")
        } catch (expected: IOException) {
            // 预期失败路径。
        }
    }

    private fun read(source: ByteSource, position: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        val read = source.readAt(position, buffer, 0, length)
        return if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { index -> (index % 251).toByte() }
}

/** 按顺序执行预读任务，让预读在测试线程上确定发生。 */
private class QueueExecutor : Executor {

    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun pending(): Int = tasks.size

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

/** 记录底层读取位置与长度、可注入失败的假字节源。 */
private class RecordingOrigin(
    private val bytes: ByteArray,
    override val maxReadBytes: Int = 0,
) : ByteSource {

    val reads = mutableListOf<Pair<Long, Int>>()

    var failure: IOException? = null

    var closed = false

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        failure?.let { throw it }
        if (position >= bytes.size) return 0
        reads += position to length
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closed = true
    }
}

/** 读取会阻塞到放行，用来构造「补齐过程中被关闭」。 */
private class BlockingOrigin(private val bytes: ByteArray) : ByteSource {

    private val lock = Object()
    private val entered = CountDownLatch(1)
    private val gate = CountDownLatch(1)

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        entered.countDown()
        gate.await(5, TimeUnit.SECONDS)
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    fun awaitReading(): Boolean = entered.await(5, TimeUnit.SECONDS)

    fun release() = gate.countDown()

    override fun close() {
        synchronized(lock) { gate.countDown() }
    }
}

/** 每次读取都要花一点时间，用来验证同时读取的路数。 */
private class SlowOrigin(private val bytes: ByteArray, private val delayMillis: Long) : ByteSource {

    private val lock = Object()
    private val entered = CountDownLatch(1)
    private val concurrent = AtomicInteger()

    @Volatile
    var maxConcurrent = 0

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val now = concurrent.incrementAndGet()
        synchronized(lock) { maxConcurrent = maxOf(maxConcurrent, now) }
        entered.countDown()
        try {
            Thread.sleep(delayMillis)
        } finally {
            concurrent.decrementAndGet()
        }
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    fun awaitReading(): Boolean = entered.await(5, TimeUnit.SECONDS)

    override fun close() = Unit
}
