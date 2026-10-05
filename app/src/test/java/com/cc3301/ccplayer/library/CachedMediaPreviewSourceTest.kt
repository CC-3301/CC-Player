package com.cc3301.ccplayer.library

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedMediaPreviewSourceTest {

    private val directory: File = Files.createTempDirectory("media-preview").toFile()
    private val extractor = FakeMediaPreviewExtractor()
    private val source = CachedMediaPreviewSource(
        extractor = extractor,
        cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20),
    )

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun `音频有内嵌封面时用封面图`() = runTest {
        val cover = byteArrayOf(1, 2, 3)
        extractor.imageBytes = cover

        val preview = source.preview(local("歌.mp3"))

        assertNotNull(preview.image)
        assertArrayEquals(cover, preview.image!!.readBytes())
        assertEquals(listOf("root/歌.mp3"), extractor.imageRequests)
    }

    @Test
    fun `音频无内嵌封面时没有图，由界面显示占位图`() = runTest {
        extractor.imageBytes = null

        val preview = source.preview(local("歌.mp3"))

        assertNull(preview.image)
        assertEquals(listOf("root/歌.mp3"), extractor.imageRequests)
    }

    @Test
    fun `视频用抽帧图`() = runTest {
        val frame = byteArrayOf(9, 8, 7)
        extractor.imageBytes = frame

        val preview = source.preview(local("电影.mkv"))

        assertArrayEquals(frame, preview.image!!.readBytes())
    }

    @Test
    fun `时长透传给界面`() = runTest {
        extractor.durationMillis = 187_000

        assertEquals(187_000L, source.preview(local("电影.mkv")).durationMillis)
    }

    @Test
    fun `已有缓存时不再抽帧`() = runTest {
        extractor.imageBytes = byteArrayOf(1, 2, 3)
        val first = source.preview(local("电影.mkv")).image
        extractor.imageBytes = null

        val second = source.preview(local("电影.mkv")).image

        assertEquals(first, second)
        assertEquals(1, extractor.imageRequests.size)
    }

    @Test
    fun `网络文件不抽帧也不读时长`() = runTest {
        val network = MediaFile(
            name = "共享电影.mkv",
            location = "smb://nas/电影.mkv",
            source = MediaSource.Network,
        )

        val preview = source.preview(network)

        assertNull(preview.image)
        assertNull(preview.durationMillis)
        assertTrue(extractor.imageRequests.isEmpty())
        assertTrue(extractor.durationRequests.isEmpty())
    }

    @Test
    fun `子目录没有预览`() = runTest {
        val preview = source.preview(testDirectory("剧集", "root/剧集"))

        assertNull(preview.image)
        assertNull(preview.durationMillis)
        assertTrue(extractor.imageRequests.isEmpty())
    }

    private fun local(name: String) = MediaFile(name = name, location = "root/$name")
}
