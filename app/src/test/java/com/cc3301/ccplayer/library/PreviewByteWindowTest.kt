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
        assertEquals(0L, origin.reads.single())
    }

    @Test
    fun `预读把后续块拉进本地，读取不再请求底层`() {
        val origin = RecordingOrigin(payload(1000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = executor)

        read(source, 0, 4)
        executor.runAll()

        // 窗口内 4 块（含当前块之后 3 块）已就绪，读取不再向底层发请求。
        val before = origin.reads.size
        read(source, 100, 8)
        read(source, 150, 8)
        assertEquals(before, origin.reads.size)
    }

    @Test
    fun `预读不超过窗口上限且不越过文件末尾`() {
        val origin = RecordingOrigin(payload(10_000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = executor)

        read(source, 0, 4)
        executor.runAll()

        // 当前块 + 窗口内后续块，合计不超过一个块加一个窗口。
        assertEquals(1 + window.toInt() / chunk, origin.reads.size)

        val tail = RecordingOrigin(payload(100))
        val tailExecutor = QueueExecutor()
        val tailSource = PreviewByteWindow(tail, windowBytes = window, chunkBytes = chunk, readAheadExecutor = tailExecutor)
        read(tailSource, 0, 4)
        tailExecutor.runAll()
        assertEquals(2, tail.reads.size)
    }

    @Test
    fun `跳到远处后只预读新位置之后的窗口`() {
        val origin = RecordingOrigin(payload(10_000))
        val executor = QueueExecutor()
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk, readAheadExecutor = executor)

        read(source, 0, 4)
        executor.runAll()
        val beforeJump = origin.reads.toList()

        read(source, 5000, 4)
        executor.runAll()
        val afterJump = origin.reads.drop(beforeJump.size)

        // 5000 落在起点 4992 的块里，补齐按块起点开始。
        assertEquals(listOf(4992L), afterJump.take(1))
        assertTrue("跳读后不再回读旧位置：$afterJump", afterJump.all { it >= 4992 })
    }

    @Test
    fun `预读与解码读取并发，同一时刻不超过两路`() {
        val origin = SlowOrigin(payload(100_000), delayMillis = 200)
        val source = PreviewByteWindow(origin, windowBytes = window, chunkBytes = chunk)

        // 当前块读完就挂上预读，预读在后台线程读后续块。
        read(source, 0, 4)
        assertTrue("预读应已开始", origin.awaitReading())
        // 解码跳到窗口外：两路同时读，合计两路。
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

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

/** 记录底层读取位置、可注入失败的假字节源。 */
private class RecordingOrigin(private val bytes: ByteArray) : ByteSource {

    val reads = mutableListOf<Long>()

    var failure: IOException? = null

    var closed = false

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        failure?.let { throw it }
        if (position >= bytes.size) return 0
        reads += position
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closed = true
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
