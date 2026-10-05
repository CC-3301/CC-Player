package com.cc3301.ccplayer.streaming

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.Closeable

class LoopbackStreamServerTest {

    private val payload = ByteArray(1000) { (it % 251).toByte() }
    private val server = LoopbackStreamServer()
    private val source = RecordingByteSource(payload)
    private val url = server.serve(source)

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `不带 Range 的 GET 返回 200 与全量字节`() {
        val response = httpRequest(url)

        assertEquals(200, response.statusCode)
        assertEquals("1000", response.header("Content-Length"))
        assertEquals("bytes", response.header("Accept-Ranges"))
        assertNull(response.header("Content-Range"))
        assertArrayEquals(payload, response.body)
    }

    @Test
    fun `带 Range 的 GET 返回 206 与对应字节`() {
        val response = httpRequest(url, range = "bytes=100-199")

        assertEquals(206, response.statusCode)
        assertEquals("bytes 100-199/1000", response.header("Content-Range"))
        assertEquals("100", response.header("Content-Length"))
        assertArrayEquals(payload.copyOfRange(100, 200), response.body)
    }

    @Test
    fun `开区间 Range 一直读到文件末尾`() {
        val response = httpRequest(url, range = "bytes=990-")

        assertEquals(206, response.statusCode)
        assertArrayEquals(payload.copyOfRange(990, 1000), response.body)
    }

    @Test
    fun `Range 越界返回 416`() {
        val response = httpRequest(url, range = "bytes=5000-6000")

        assertEquals(416, response.statusCode)
        assertEquals("bytes */1000", response.header("Content-Range"))
        assertEquals(0, response.body.size)
    }

    @Test
    fun `HEAD 只返回头部`() {
        val response = httpRequest(url, method = "HEAD")

        assertEquals(200, response.statusCode)
        assertEquals("1000", response.header("Content-Length"))
        assertEquals(0, response.body.size)
    }

    @Test
    fun `未知流向返回 404`() {
        val response = httpRequest("http://127.0.0.1:${server.port}/4040")

        assertEquals(404, response.statusCode)
    }

    @Test
    fun `关闭后登记流的字节源一并关闭`() {
        server.close()

        assertEquals(true, source.closed)
    }
}

/** 记录是否被关闭的字节源。 */
class RecordingByteSource(private val bytes: ByteArray) : ByteSource, Closeable {

    var closed = false

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closed = true
    }
}
