package com.cc3301.ccplayer.library

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
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
    fun `网络视频抽帧并显示，对外不返回时长`() = runTest {
        val frame = byteArrayOf(4, 5, 6)
        extractor.imageBytes = frame

        val preview = source.preview(network("共享电影.mkv"))

        assertArrayEquals(frame, preview.image!!.readBytes())
        assertEquals(listOf("smb://nas/media/共享电影.mkv"), extractor.imageRequests)
        assertEquals(1, extractor.internalDurationReads)
        assertTrue(extractor.durationRequests.isEmpty())
        assertNull(preview.durationMillis)
    }

    @Test
    fun `网络音频对外不返回时长`() = runTest {
        extractor.imageBytes = byteArrayOf(7, 8, 9)

        val preview = source.preview(network("歌.mp3"))

        assertNull(preview.durationMillis)
        assertEquals(0, extractor.internalDurationReads)
        assertTrue(extractor.durationRequests.isEmpty())
    }

    @Test
    fun `网络音频提取内嵌封面并显示`() = runTest {
        val cover = byteArrayOf(7, 8, 9)
        extractor.imageBytes = cover

        val preview = source.preview(network("歌.mp3"))

        assertArrayEquals(cover, preview.image!!.readBytes())
        assertEquals(listOf("smb://nas/media/歌.mp3"), extractor.imageRequests)
    }

    @Test
    fun `网络预览再次取用缓存，不再抽取`() = runTest {
        extractor.imageBytes = byteArrayOf(1, 2, 3)
        val first = source.preview(network("电影.mkv")).image
        extractor.imageBytes = null

        val second = source.preview(network("电影.mkv")).image

        assertEquals(first, second)
        assertEquals(1, extractor.imageRequests.size)
    }

    @Test
    fun `网络缓存键包含修改时间，修改时间变化后重新抽取`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        source.preview(network("电影.mkv", lastModified = 1_000))
        extractor.imageBytes = byteArrayOf(2)

        val changed = source.preview(network("电影.mkv", lastModified = 2_000))

        assertArrayEquals(byteArrayOf(2), changed.image!!.readBytes())
        assertEquals(2, extractor.imageRequests.size)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `不同位置相同修改时间各自独立缓存`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        source.preview(networkAt("a.mkv", lastModified = 1_000))
        source.preview(networkAt("b.mkv", lastModified = 1_000))

        assertEquals(2, imageFileCount())

        source.preview(networkAt("a.mkv", lastModified = 1_000))
        source.preview(networkAt("b.mkv", lastModified = 1_000))

        assertEquals(2, extractor.imageRequests.size)
    }

    @Test
    fun `修改时间缺失时按大小与有效期缓存`() = runTest {
        var now = 1_000L
        val fallback = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, clock = { now }),
        )
        extractor.imageBytes = byteArrayOf(1)

        val first = fallback.preview(network("电影.mkv", lastModified = 0))
        now += MISSING_MODIFIED_VALIDITY_MILLIS - 1
        val second = fallback.preview(network("电影.mkv", lastModified = 0))

        assertNotNull(first.image)
        assertEquals(first.image, second.image)
        assertEquals(1, extractor.imageRequests.size)
        assertEquals(1, imageFileCount())

        now += 2
        extractor.imageBytes = byteArrayOf(2)
        val expired = fallback.preview(network("电影.mkv", lastModified = 0))

        assertArrayEquals(byteArrayOf(2), expired.image!!.readBytes())
        assertEquals(2, extractor.imageRequests.size)
    }

    @Test
    fun `修改时间缺失时有效期变化不命中旧缓存`() = runTest {
        val cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, clock = { 1_000L })
        val shortLived = CachedMediaPreviewSource(
            extractor = extractor,
            cache = cache,
            policy = ThumbnailPolicy(missingModifiedValidityMillis = 1_000),
        )
        val longLived = CachedMediaPreviewSource(
            extractor = extractor,
            cache = cache,
            policy = ThumbnailPolicy(missingModifiedValidityMillis = 2_000),
        )
        extractor.imageBytes = byteArrayOf(1)

        shortLived.preview(network("电影.mkv", lastModified = 0))
        longLived.preview(network("电影.mkv", lastModified = 0))

        assertEquals(2, extractor.imageRequests.size)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `缓存命中时并发读取时长只发生一次`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = 187_000
        val first = source.preview(local("电影.mkv"))
        assertEquals(187_000L, first.durationMillis)
        val callsAfterFirst = extractor.durationRequests.size

        extractor.durationDelayMillis = 10
        val results = (1..5).map { async { source.preview(local("电影.mkv")) } }.awaitAll()

        assertTrue(results.all { it.durationMillis == 187_000L })
        assertEquals(callsAfterFirst + 1, extractor.durationRequests.size)
    }

    @Test
    fun `缓存键包含文件大小，大小变化后重新抽取`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        source.preview(local("电影.mkv", size = 100))
        extractor.imageBytes = byteArrayOf(2)

        val changed = source.preview(local("电影.mkv", size = 200))

        assertArrayEquals(byteArrayOf(2), changed.image!!.readBytes())
        assertEquals(2, extractor.imageRequests.size)
    }

    @Test
    fun `缓存键包含抽帧策略版本`() = runTest {
        val first = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20),
            policy = ThumbnailPolicy(version = 1),
        )
        val second = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20),
            policy = ThumbnailPolicy(version = 2),
        )
        extractor.imageBytes = byteArrayOf(1)

        first.preview(local("电影.mkv"))
        second.preview(local("电影.mkv"))

        assertEquals(2, extractor.imageRequests.size)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `缓存键包含目标宽度`() = runTest {
        val first = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20),
            policy = ThumbnailPolicy(targetWidth = 320),
        )
        val second = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20),
            policy = ThumbnailPolicy(targetWidth = 512),
        )
        extractor.imageBytes = byteArrayOf(1)

        first.preview(local("电影.mkv"))
        second.preview(local("电影.mkv"))

        assertEquals(2, extractor.imageRequests.size)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `同一媒体并发请求只抽取一次`() = runTest {
        val tracker = ConcurrentExtractor()
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20))

        val previews = (1..5).map { async { bounded.preview(network("电影.mkv")) } }
        val results = previews.awaitAll()

        assertEquals(1, tracker.requests)
        assertTrue(results.all { it.image != null })
    }

    @Test
    fun `同一媒体并发请求失败时也只抽取一次`() = runTest {
        val tracker = ConcurrentExtractor(failure = MediaSourceException(MediaFailure.Unreachable, "连不上"))
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20))

        val results = (1..5).map { async { bounded.preview(network("电影.mkv")) } }.awaitAll()

        assertEquals(1, tracker.requests)
        assertTrue(results.all { it.image == null })
    }

    @Test
    fun `本地并发提取不超过 2`() = runTest {
        val tracker = ConcurrentExtractor()
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20))

        val previews = (1..10).map { index -> async { bounded.preview(local("第$index 集.mkv")) } }
        previews.awaitAll()

        assertTrue(tracker.maxConcurrent <= 2)
        assertEquals(10, tracker.requests)
    }

    @Test
    fun `网络提取失败保持占位图且不写缓存`() = runTest {
        extractor.imageFailure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        val preview = source.preview(network("电影.mkv"))

        assertNull(preview.image)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `网络提取超时保持占位图且不写缓存`() = runTest {
        extractor.imageDelayMillis = 60_000

        val preview = source.preview(network("电影.mkv"))

        assertNull(preview.image)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `慢提取超时后释放并发名额`() = runTest {
        val tracker = SlowFileExtractor(slowDelayMillis = 60_000)
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20))
        val slow = (1..3).map { index -> async { bounded.preview(network("慢$index.mkv")) } }
        runCurrent()
        assertEquals(3, tracker.requests.size)

        val fast = async { bounded.preview(network("快.mkv")) }
        val fastPreview = fast.await()

        assertNotNull(fastPreview.image)
        assertTrue(tracker.requests.contains("smb://nas/media/快.mkv"))
        assertTrue(slow.awaitAll().all { it.image == null })
    }

    @Test
    fun `同一目录并发提取有上限`() = runTest {
        val tracker = ConcurrentExtractor()
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20))

        val previews = (1..10).map { index -> async { bounded.preview(network("第$index 集.mkv")) } }
        previews.awaitAll()

        assertTrue(tracker.maxConcurrent <= 3)
        assertEquals(10, tracker.requests)
    }

    @Test
    fun `子目录没有预览`() = runTest {
        val preview = source.preview(testDirectory("剧集", "root/剧集"))

        assertNull(preview.image)
        assertNull(preview.durationMillis)
        assertTrue(extractor.imageRequests.isEmpty())
    }

    private fun imageFileCount(): Int =
        directory.listFiles().orEmpty().count { file -> file.name.endsWith(".img") }

    private fun local(name: String, size: Long = 0) =
        MediaFile(name = name, location = "root/$name", size = size)

    private fun network(name: String, lastModified: Long = 1_700_000_000_000) = networkAt(name, lastModified)

    private fun networkAt(name: String, lastModified: Long) = MediaFile(
        name = name,
        location = "smb://nas/media/$name",
        lastModified = lastModified,
        source = MediaSource.Network,
    )
}

/** 名字以“慢”开头的条目会先等一段时间，用来验证超时后的并发名额释放。 */
private class SlowFileExtractor(private val slowDelayMillis: Long) : MediaPreviewExtractor {

    val requests = mutableListOf<String>()

    override suspend fun imageBytes(file: MediaFile): ByteArray? {
        requests += file.location
        if (file.name.startsWith("慢")) delay(slowDelayMillis)
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 记录同时进行的抽图数，用来验证并发上限；可注入失败。 */
private class ConcurrentExtractor(private val failure: Exception? = null) : MediaPreviewExtractor {

    var requests = 0
    var concurrent = 0
    var maxConcurrent = 0

    override suspend fun imageBytes(file: MediaFile): ByteArray? {
        requests++
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        yield()
        failure?.let { problem ->
            concurrent--
            throw problem
        }
        concurrent--
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}
