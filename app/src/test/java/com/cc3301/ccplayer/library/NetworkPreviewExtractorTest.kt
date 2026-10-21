package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkPreviewExtractorTest {

    private val payload = ByteArray(64) { it.toByte() }
    private val source = RecordingByteSource(payload)
    private val reads = RecordingReads(source)
    private val movie = MediaFile(
        name = "电影.mkv",
        location = "smb://nas/media/电影.mkv",
        lastModified = 1_700_000_000_000,
        source = MediaSource.Network,
    )

    @Test
    fun `网络取图成功时返回解码结果并关闭句柄`() = runTest {
        var askedKind: MediaKind? = null
        val extractor = NetworkPreviewExtractor(reads) { opened, kind, _ ->
            askedKind = kind
            ByteArray(8).also { buffer -> opened.readAt(0, buffer, 0, buffer.size) }
        }

        assertArrayEquals(payload.copyOfRange(0, 8), extractor.imageBytes(movie)!!)
        assertEquals(MediaKind.Video, askedKind)
        assertEquals(listOf(movie.location), reads.opened)
        assertEquals(listOf(0L), source.reads)
        assertTrue(source.closed)
    }

    @Test
    fun `网络取图使用调用方的目标宽度`() = runTest {
        var askedWidth: Int? = null
        val extractor = NetworkPreviewExtractor(reads) { _, _, width ->
            askedWidth = width
            ByteArray(1)
        }

        extractor.imageBytes(movie, targetWidth = 320)

        assertEquals(320, askedWidth)
    }

    @Test
    fun `音频按内嵌封面取图`() = runTest {
        var askedKind: MediaKind? = null
        val extractor = NetworkPreviewExtractor(reads) { _, kind, _ ->
            askedKind = kind
            ByteArray(2)
        }

        extractor.imageBytes(movie.copy(name = "歌.mp3"))

        assertEquals(MediaKind.Audio, askedKind)
        assertTrue(source.closed)
    }

    @Test
    fun `解码抛异常时向调用方抛出并关闭句柄`() = runTest {
        val extractor = NetworkPreviewExtractor(reads) { _, _, _ -> error("解码失败") }

        try {
            extractor.imageBytes(movie)
            fail("应抛出解码异常")
        } catch (expected: IllegalStateException) {
            // 预期失败路径。
        }
        assertTrue(source.closed)
    }

    @Test
    fun `打开句柄失败时向调用方抛出`() = runTest {
        val extractor = NetworkPreviewExtractor({ error("打不开") }) { _, _, _ -> ByteArray(1) }

        try {
            extractor.imageBytes(movie)
            fail("应抛出打开失败")
        } catch (expected: IllegalStateException) {
            // 预期失败路径。
        }
    }

    @Test
    fun `非媒体文件不打开句柄`() = runTest {
        val extractor = NetworkPreviewExtractor(reads) { _, _, _ -> ByteArray(1) }

        assertNull(extractor.imageBytes(movie.copy(name = "说明.txt")))
        assertTrue(reads.opened.isEmpty())
    }

    @Test
    fun `读取位置越界与末尾返回 -1`() {
        val buffer = ByteArray(8)

        assertEquals(-1, rangeReadAt(source, -1, buffer, 0, buffer.size))
        assertEquals(-1, rangeReadAt(source, payload.size.toLong(), buffer, 0, buffer.size))
        assertEquals(-1, rangeReadAt(source, payload.size + 10L, buffer, 0, buffer.size))
        assertTrue(source.reads.isEmpty())
    }

    @Test
    fun `短读按实际字节数返回`() {
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

/** 记录读取位置与关闭状态的假随机读句柄。 */
private class RecordingByteSource(private val bytes: ByteArray) : ByteSource {

    var closed = false
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
    }
}

/** 记录打开位置的假读取接缝。 */
private class RecordingReads(private val source: ByteSource) : NetworkMediaReads {

    val opened = mutableListOf<String>()

    override suspend fun openRead(location: String): ByteSource {
        opened += location
        return source
    }
}
