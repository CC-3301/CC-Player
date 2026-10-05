package com.cc3301.ccplayer.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteRangeTest {

    private val size = 1000L

    @Test
    fun `没有 Range 头时返回全量 200`() {
        val response = byteRangeResponse(rangeHeader = null, totalSize = size)

        assertEquals(200, response.statusCode)
        assertEquals(0L, response.start)
        assertEquals(999L, response.endInclusive)
        assertEquals(1000L, response.contentLength)
        assertNull(response.contentRange)
    }

    @Test
    fun `Range 头不合法时按全量返回 200`() {
        listOf("items=0-99", "bytes=abc", "bytes=", "bytes=-", "bytes=0-99,200-299").forEach { header ->
            val response = byteRangeResponse(header, size)

            assertEquals("头：$header", 200, response.statusCode)
            assertEquals("头：$header", 0L, response.start)
            assertEquals("头：$header", 999L, response.endInclusive)
        }
    }

    @Test
    fun `单段 Range 返回 206 与 Content-Range`() {
        val response = byteRangeResponse("bytes=0-999", size)

        assertEquals(206, response.statusCode)
        assertEquals(0L, response.start)
        assertEquals(999L, response.endInclusive)
        assertEquals(1000L, response.contentLength)
        assertEquals("bytes 0-999/1000", response.contentRange)
    }

    @Test
    fun `末段超出文件末尾时截断到末尾`() {
        val response = byteRangeResponse("bytes=900-2000", size)

        assertEquals(206, response.statusCode)
        assertEquals(900L, response.start)
        assertEquals(999L, response.endInclusive)
        assertEquals(100L, response.contentLength)
        assertEquals("bytes 900-999/1000", response.contentRange)
    }

    @Test
    fun `开区间 Range 一直到文件末尾`() {
        val response = byteRangeResponse("bytes=1000-", size)

        assertEquals(416, response.statusCode)
        assertEquals(0L, response.contentLength)
        assertEquals("bytes */1000", response.contentRange)

        val openEnded = byteRangeResponse("bytes=100-", size)
        assertEquals(206, openEnded.statusCode)
        assertEquals(100L, openEnded.start)
        assertEquals(999L, openEnded.endInclusive)
        assertEquals("bytes 100-999/1000", openEnded.contentRange)
    }

    @Test
    fun `起点越界返回 416`() {
        val response = byteRangeResponse("bytes=5000-6000", size)

        assertEquals(416, response.statusCode)
        assertEquals(0L, response.contentLength)
        assertEquals("bytes */1000", response.contentRange)
    }

    @Test
    fun `后缀 Range 取末尾若干字节`() {
        val response = byteRangeResponse("bytes=-500", size)

        assertEquals(206, response.statusCode)
        assertEquals(500L, response.start)
        assertEquals(999L, response.endInclusive)
        assertEquals("bytes 500-999/1000", response.contentRange)
    }

    @Test
    fun `后缀长于文件时返回全量 206`() {
        val response = byteRangeResponse("bytes=-5000", size)

        assertEquals(206, response.statusCode)
        assertEquals(0L, response.start)
        assertEquals(999L, response.endInclusive)
        assertEquals("bytes 0-999/1000", response.contentRange)
    }

    @Test
    fun `空文件不带 Range 时返回 200 且长度为 0`() {
        val response = byteRangeResponse(null, 0)

        assertEquals(200, response.statusCode)
        assertEquals(0L, response.contentLength)
    }

    @Test
    fun `空文件带 Range 时返回 416`() {
        val response = byteRangeResponse("bytes=0-", 0)

        assertEquals(416, response.statusCode)
        assertEquals("bytes */0", response.contentRange)
    }
}
