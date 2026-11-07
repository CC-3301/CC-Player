package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteSourceDataSourceTest {

    private val payload = ByteArray(64) { it.toByte() }
    private val source = FakeSource(payload)

    @Test
    fun `按数据源位置顺序读取并推进`() {
        val cursor = ByteSourceReadCursor(source)

        assertEquals(64L, cursor.open(0))
        val first = ByteArray(8)
        assertEquals(8, cursor.read(first, 0, first.size))
        assertArrayEquals(payload.copyOfRange(0, 8), first)

        val second = ByteArray(8)
        assertEquals(8, cursor.read(second, 0, second.size))
        assertArrayEquals(payload.copyOfRange(8, 16), second)
    }

    @Test
    fun `从中间位置打开时剩余长度按文件末尾算`() {
        val cursor = ByteSourceReadCursor(source)

        assertEquals(56L, cursor.open(8))
        val buffer = ByteArray(8)
        assertEquals(8, cursor.read(buffer, 0, buffer.size))
        assertArrayEquals(payload.copyOfRange(8, 16), buffer)
    }

    @Test
    fun `读到末尾返回 -1`() {
        val cursor = ByteSourceReadCursor(source)
        cursor.open(payload.size.toLong())

        assertEquals(-1, cursor.read(ByteArray(4), 0, 4))
    }

    @Test
    fun `短读按实际字节返回`() {
        val cursor = ByteSourceReadCursor(source)
        cursor.open(60)

        val buffer = ByteArray(8)
        assertEquals(4, cursor.read(buffer, 0, buffer.size))
        assertArrayEquals(payload.copyOfRange(60, 64), buffer.copyOfRange(0, 4))
    }

    @Test
    fun `长度为 0 的读取返回 0`() {
        val cursor = ByteSourceReadCursor(source)
        cursor.open(0)

        assertEquals(0, cursor.read(ByteArray(4), 0, 0))
    }

    @Test
    fun `打开位置超出文件长度按越界处理`() {
        val cursor = ByteSourceReadCursor(source)

        try {
            cursor.open(payload.size + 1L)
            throw AssertionError("应抛越界异常")
        } catch (expected: EOFException) {
            // 预期路径。
        }
    }

    @Test
    fun `关闭数据源不关闭调用方的随机读句柄`() {
        val cursor = ByteSourceReadCursor(source)
        cursor.open(0)

        cursor.close()

        assertFalse("句柄由预览读取层关闭", source.closed)
        assertEquals(-1, cursor.read(ByteArray(1), 0, 1))
    }

    @Test
    fun `数据源关闭不关闭底层句柄`() {
        ByteSourceDataSource(source, uri = null).close()

        assertFalse(source.closed)
    }
}

private class FakeSource(private val payload: ByteArray) : ByteSource {

    var closed = false
        private set

    override val size: Long get() = payload.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= payload.size) return 0
        val count = minOf(length.toLong(), payload.size - position).toInt()
        payload.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closed = true
    }
}
