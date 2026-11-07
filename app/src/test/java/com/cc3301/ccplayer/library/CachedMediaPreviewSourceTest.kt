package com.cc3301.ccplayer.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedMediaPreviewSourceTest {

    private val directory: File = Files.createTempDirectory("media-preview").toFile()
    private val extractor = FakeMediaPreviewExtractor()
    private val source = CachedMediaPreviewSource(
        extractor = extractor,
        cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        backgroundScope = unconfinedBackgroundScope,
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
        val file = network("共享电影.mkv")

        val preview = source.preview(file)

        assertArrayEquals(frame, preview.image!!.readBytes())
        assertEquals(listOf("smb://nas/media/共享电影.mkv"), extractor.imageRequests)
        assertEquals(listOf(320), extractor.imageWidths)
        assertTrue(extractor.durationRequests.isEmpty())
        assertNull(preview.durationMillis)
        val entry = cache().entry(cacheKey(file))
        assertNotNull(entry)
        assertNull(entry!!.durationMillis)
        assertTrue(!entry.durationKnown)
    }

    @Test
    fun `网络候选帧全为纯色时保持占位图`() = runTest {
        val solid = SolidNetworkPreviewExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = solid,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )

        val preview = bounded.preview(network("纯色.mkv"))

        assertNull(preview.image)
        assertEquals(3, solid.decodes)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `网络音频对外不返回时长`() = runTest {
        extractor.imageBytes = byteArrayOf(7, 8, 9)

        val preview = source.preview(network("歌.mp3"))

        assertNull(preview.durationMillis)
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
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined, clock = { now }),
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
        val cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined, clock = { 1_000L })
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
    fun `顺序命中缓存不重复读取时长`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = 187_000
        source.preview(local("电影.mkv"))
        val callsAfterFirst = extractor.durationRequests.size

        val second = source.preview(local("电影.mkv"))

        assertEquals(187_000L, second.durationMillis)
        assertEquals(callsAfterFirst, extractor.durationRequests.size)
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
        assertEquals(callsAfterFirst, extractor.durationRequests.size)
    }

    @Test
    fun `旧格式单行元数据命中后先出图，后台补读时长并回写`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = 187_000
        val file = local("电影.mkv", lastModified = 1_000)
        source.preview(file)
        val key = cacheKey(file)
        metadataFile().writeText("1000")
        val callsAfterFirst = extractor.durationRequests.size

        val hit = source.preview(file)

        // 命中先出图，时长在后台补读后回写缓存。
        assertNotNull(hit.image)
        assertNull(hit.durationMillis)
        assertEquals(callsAfterFirst + 1, extractor.durationRequests.size)
        val written = cache().entry(key)
        assertNotNull(written)
        assertEquals(187_000L, written!!.durationMillis)
        assertTrue(written.durationKnown)

        val second = source.preview(file)
        assertEquals(187_000L, second.durationMillis)
        assertEquals(callsAfterFirst + 1, extractor.durationRequests.size)
    }

    @Test
    fun `无元数据的旧条目命中后先出图，后台补读时长并回写`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = 187_000
        val file = local("电影.mkv", lastModified = 1_000)
        source.preview(file)
        val key = cacheKey(file)
        metadataFile().delete()
        val callsAfterFirst = extractor.durationRequests.size

        val hit = source.preview(file)

        assertNotNull(hit.image)
        assertNull(hit.durationMillis)
        assertEquals(callsAfterFirst + 1, extractor.durationRequests.size)
        val written = cache().entry(key)
        assertNotNull(written)
        assertEquals(187_000L, written!!.durationMillis)
        assertTrue(written.durationKnown)

        assertEquals(187_000L, source.preview(file).durationMillis)
        assertEquals(callsAfterFirst + 1, extractor.durationRequests.size)
    }

    @Test
    fun `抽取阶段读时长失败保留图片且不落失败标记`() = runTest {
        var now = 1_000L
        val cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined, clock = { now })
        val bounded = CachedMediaPreviewSource(
            extractor = extractor,
            cache = cache,
            monotonicClockMillis = { now },
            backgroundScope = unconfinedBackgroundScope,
        )
        val image = byteArrayOf(1, 2, 3)
        extractor.imageBytes = image
        extractor.durationMillis = 187_000
        extractor.durationFailure = IllegalStateException("读时长失败")
        val file = local("电影.mkv", lastModified = 1_000)

        val preview = bounded.preview(file)

        assertArrayEquals(image, preview.image!!.readBytes())
        assertNull(preview.durationMillis)
        val entry = cache.entry(cacheKey(file))
        assertNotNull(entry)
        assertTrue(!entry!!.durationKnown)
    }

    @Test
    fun `命中缓存时读时长失败保留图片且保持未知`() = runTest {
        var now = 1_000L
        val cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined, clock = { now })
        val bounded = CachedMediaPreviewSource(
            extractor = extractor,
            cache = cache,
            monotonicClockMillis = { now },
            backgroundScope = unconfinedBackgroundScope,
        )
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = 187_000
        extractor.durationFailure = IllegalStateException("读时长失败")
        val file = local("电影.mkv", lastModified = 1_000)

        val first = bounded.preview(file)
        assertNotNull(first.image)
        assertNull(first.durationMillis)
        val key = cacheKey(file)
        assertTrue(!cache.entry(key)!!.durationKnown)

        val hit = bounded.preview(file)

        assertNotNull(hit.image)
        assertNull(hit.durationMillis)
        assertTrue(!cache.entry(key)!!.durationKnown)

        now += 60 * 60 * 1000L
        extractor.durationFailure = null
        // 退避到期后的命中仍先出图，补读结果回写缓存，下一次命中带时长。
        assertNotNull(bounded.preview(file).image)
        val written = cache.entry(key)
        assertNotNull(written)
        assertEquals(187_000L, written!!.durationMillis)
        assertTrue(written.durationKnown)
        assertEquals(187_000L, bounded.preview(file).durationMillis)
    }

    @Test
    fun `读时长失败后退避，退避期内不再重试`() = runTest {
        var now = 1_000L
        val cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined, clock = { now })
        val bounded = CachedMediaPreviewSource(
            extractor = extractor,
            cache = cache,
            monotonicClockMillis = { now },
            backgroundScope = unconfinedBackgroundScope,
        )
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = 187_000
        extractor.durationFailure = IllegalStateException("读时长失败")
        val file = local("电影.mkv", lastModified = 1_000)
        bounded.preview(file)
        val callsBeforeFailure = extractor.durationRequests.size

        assertNull(bounded.preview(file).durationMillis)
        assertEquals(callsBeforeFailure + 1, extractor.durationRequests.size)

        assertNull(bounded.preview(file).durationMillis)
        assertNull(bounded.preview(file).durationMillis)
        assertEquals(callsBeforeFailure + 1, extractor.durationRequests.size)

        now += 60 * 60 * 1000L
        extractor.durationFailure = null
        assertNull(bounded.preview(file).durationMillis)
        assertEquals(callsBeforeFailure + 2, extractor.durationRequests.size)
        assertEquals(187_000L, bounded.preview(file).durationMillis)
        assertEquals(callsBeforeFailure + 2, extractor.durationRequests.size)
    }

    @Test
    fun `确定没有时长时写入元数据且后续命中不再重读`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = null
        val file = local("电影.mkv", lastModified = 1_000)

        assertNull(source.preview(file).durationMillis)
        val callsAfterFirst = extractor.durationRequests.size

        assertNull(source.preview(file).durationMillis)
        assertNull(source.preview(file).durationMillis)

        assertEquals(callsAfterFirst, extractor.durationRequests.size)
        val entry = cache().entry(cacheKey(file))
        assertNotNull(entry)
        assertTrue(entry!!.durationKnown)
        assertNull(entry.durationMillis)
    }

    @Test
    fun `无预览图时记录时长，下次命中不再读取`() = runTest {
        extractor.imageBytes = null
        extractor.durationMillis = 187_000
        val file = local("歌.mp3", lastModified = 1_000)

        val first = source.preview(file)
        assertNull(first.image)
        assertEquals(187_000L, first.durationMillis)
        val callsAfterFirst = extractor.durationRequests.size

        val second = source.preview(file)

        assertNull(second.image)
        assertEquals(187_000L, second.durationMillis)
        assertEquals(callsAfterFirst, extractor.durationRequests.size)
    }

    @Test
    fun `无图条目修改时间缺失且有效期到期后重新抽取`() = runTest {
        var now = 1_000L
        val cache = ThumbnailCache(
            directory = directory,
            maxBytes = 1 shl 20,
            clock = { now },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val bounded = CachedMediaPreviewSource(
            extractor = extractor,
            cache = cache,
            monotonicClockMillis = { now },
            backgroundScope = unconfinedBackgroundScope,
        )
        extractor.imageBytes = null
        extractor.durationMillis = 5_000
        val file = local("歌.mp3", lastModified = 0)

        assertNull(bounded.preview(file).image)
        assertEquals(1, extractor.imageRequests.size)

        now += MISSING_MODIFIED_VALIDITY_MILLIS - 1
        assertNull(bounded.preview(file).image)
        assertEquals(1, extractor.imageRequests.size)

        now += 2
        extractor.imageBytes = byteArrayOf(1, 2)
        val fresh = bounded.preview(file)

        assertArrayEquals(byteArrayOf(1, 2), fresh.image!!.readBytes())
        assertEquals(2, extractor.imageRequests.size)
    }

    @Test
    fun `无图条目时长未知时后续命中补读时长`() = runTest {
        extractor.imageBytes = null
        extractor.durationMillis = 187_000
        extractor.durationFailure = IllegalStateException("读时长失败")
        val file = local("歌.mp3", lastModified = 1_000)
        val key = cacheKey(file)

        val first = source.preview(file)
        assertNull(first.image)
        assertNull(first.durationMillis)
        val entry = cache().entry(key)
        assertNotNull(entry)
        assertNull(entry!!.file)
        assertTrue(!entry.durationKnown)

        extractor.durationFailure = null
        val hit = source.preview(file)

        assertNull(hit.image)
        // 命中先返回，时长在后台补读后回写缓存。
        assertNull(hit.durationMillis)
        val updated = cache().entry(key)
        assertNotNull(updated)
        assertEquals(187_000L, updated!!.durationMillis)
        assertTrue(updated.durationKnown)
        assertEquals(187_000L, source.preview(file).durationMillis)
    }

    @Test
    fun `候选帧全部解码失败不写无图条目，退避后可重试`() = runTest {
        var now = 1_000L
        val tracker = RetryAfterFailureExtractor()
        val cache = ThumbnailCache(
            directory = directory,
            maxBytes = 1 shl 20,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = cache,
            monotonicClockMillis = { now },
            backgroundScope = unconfinedBackgroundScope,
        )
        val file = local("电影.mkv", lastModified = 1_000)

        assertNull(bounded.preview(file).image)
        assertEquals(1, tracker.requests)
        assertEquals(0, directory.listFiles().orEmpty().count { it.name.endsWith(".meta") })

        now += 30_000
        assertNull(bounded.preview(file).image)
        assertEquals(2, tracker.requests)

        now += 60_000
        tracker.failure = null
        assertNotNull(bounded.preview(file).image)
        assertEquals(3, tracker.requests)
        assertEquals(1, imageFileCount())
    }

    @Test
    fun `整次抽取失败不写缓存并标记可重试，成功后恢复`() = runTest {
        val tracker = RetryAfterFailureExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val file = network("电影.mkv")

        val failed = bounded.preview(file)

        assertNull(failed.image)
        assertTrue("失败结果交给调度器重试", failed.retryable)
        assertEquals(1, tracker.requests)
        assertTrue("失败不落缓存", directory.listFiles().orEmpty().isEmpty())

        // 重试节奏由可见请求调度器安排，这里再请求就立刻重抽。
        assertNull(bounded.preview(file).image)
        assertEquals(2, tracker.requests)

        tracker.failure = null
        assertNotNull(bounded.preview(file).image)
        assertEquals(3, tracker.requests)

        // 成功后命中缓存，不再抽取。
        assertNotNull(bounded.preview(file).image)
        assertEquals(3, tracker.requests)
    }

    private fun cache(): ThumbnailCache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined)

    @Test
    fun `真实读取失败不写缓存并按退避重试`() = runTest {
        var now = 1_000L
        val filesDirectory = Files.createTempDirectory("preview-real-failure").toFile()
        try {
            val reading = FileReadingExtractor()
            val bounded = CachedMediaPreviewSource(
            extractor = reading,
                cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
                monotonicClockMillis = { now },
            )
            val file = MediaFile(name = "电影.mkv", location = File(filesDirectory, "电影.mkv").path)

            val failed = bounded.preview(file)

            assertNull(failed.image)
            assertTrue(directory.listFiles().isNullOrEmpty())

            File(file.location).writeBytes(byteArrayOf(1, 2, 3))
            now += 30_000
            val retried = bounded.preview(file)

            assertArrayEquals(byteArrayOf(1, 2, 3), retried.image!!.readBytes())
        } finally {
            filesDirectory.deleteRecursively()
        }
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
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(version = 1),
        )
        val second = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
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
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 320),
        )
        val second = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 512),
        )
        extractor.imageBytes = byteArrayOf(1)

        first.preview(local("电影.mkv"))
        second.preview(local("电影.mkv"))

        assertEquals(2, extractor.imageRequests.size)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `缓存键使用有效目标宽度`() = runTest {
        val tooNarrow = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 100),
        )
        val minimum = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 320),
        )
        extractor.imageBytes = byteArrayOf(1)

        tooNarrow.preview(local("电影.mkv"))
        minimum.preview(local("电影.mkv"))

        assertEquals(1, extractor.imageRequests.size)
        assertEquals(1, imageFileCount())
    }

    @Test
    fun `音频缓存键包含目标宽度`() = runTest {
        val first = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 320),
        )
        val second = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 512),
        )
        extractor.imageBytes = byteArrayOf(1)

        first.preview(local("歌.mp3"))
        second.preview(local("歌.mp3"))

        assertEquals(2, extractor.imageRequests.size)
        assertEquals(listOf(320, 512), extractor.imageWidths)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `网络目标宽度固定为 320，不受策略宽度影响`() = runTest {
        val wide = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 512),
        )
        val narrow = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 320),
        )
        extractor.imageBytes = byteArrayOf(1)

        wide.preview(network("电影.mkv"))
        narrow.preview(network("电影.mkv"))

        assertEquals(1, extractor.imageRequests.size)
        assertEquals(listOf(320), extractor.imageWidths)
        assertEquals(1, imageFileCount())
    }

    @Test
    fun `旧策略版本的缓存图不被新策略复用`() = runTest {
        val file = local("电影.mkv", lastModified = 1_000)
        val cache = cache()
        cache.save(
            location = cacheKey(file, ThumbnailPolicy(version = THUMBNAIL_POLICY_VERSION - 1)),
            bytes = byteArrayOf(9, 9, 9),
            durationMillis = 1,
        )
        extractor.imageBytes = byteArrayOf(1)

        val preview = CachedMediaPreviewSource(
            extractor = extractor, cache = cache).preview(file)

        assertArrayEquals(byteArrayOf(1), preview.image!!.readBytes())
        assertEquals(1, extractor.imageRequests.size)
    }

    @Test
    fun `抽取器收到钳制后的目标宽度`() = runTest {
        val narrow = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 100),
        )
        val wide = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 4_096),
        )
        extractor.imageBytes = byteArrayOf(1)

        narrow.preview(local("窄.mkv", lastModified = 1_000))
        wide.preview(local("宽.mkv", lastModified = 1_000))
        wide.preview(network("网络.mkv"))

        assertEquals(listOf(320, 512, 320), extractor.imageWidths)
    }

    @Test
    fun `首个调用者取消后等待者重新抽取`() = runTest {
        val tracker = RetryExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val leader = launch { bounded.preview(network("电影.mkv")) }
        runCurrent()
        val waiter = async { bounded.preview(network("电影.mkv")) }
        runCurrent()

        leader.cancel()
        val result = waiter.await()

        assertNotNull(result.image)
        assertEquals(2, tracker.requests)
    }

    @Test
    fun `抽取抛 Error 时等待者不会永久阻塞`() = runTest {
        val tracker = ErrorOnceExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val leader = async { runCatching { bounded.preview(network("电影.mkv")) } }
        runCurrent()
        val waiter = async { bounded.preview(network("电影.mkv")) }
        runCurrent()

        assertTrue(leader.await().isFailure)
        assertNotNull(waiter.await().image)
        assertEquals(2, tracker.requests)
    }

    @Test
    fun `取消慢提取后不写入缓存`() = runTest {
        val tracker = SlowFileExtractor(slowDelayMillis = 60_000)
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val job = launch { bounded.preview(network("慢.mkv")) }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `本地取消慢提取后不写入缓存`() = runTest {
        val tracker = SlowFileExtractor(slowDelayMillis = 60_000)
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val job = launch { bounded.preview(local("慢.mkv")) }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `取消后同键可重新抽取`() = runTest {
        val tracker = RetryExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val job = launch { bounded.preview(network("电影.mkv")) }
        runCurrent()

        job.cancel()
        job.join()

        val retried = bounded.preview(network("电影.mkv"))
        assertNotNull(retried.image)
        assertEquals(2, tracker.requests)
    }

    @Test
    fun `同一媒体并发请求只抽取一次`() = runTest {
        val tracker = ConcurrentExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

        val previews = (1..5).map { async { bounded.preview(network("电影.mkv")) } }
        val results = previews.awaitAll()

        assertEquals(1, tracker.requests)
        assertTrue(results.all { it.image != null })
    }

    @Test
    fun `同一媒体并发请求失败时也只抽取一次`() = runTest {
        val tracker = ConcurrentExtractor(failure = MediaSourceException(MediaFailure.Unreachable, "连不上"))
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

        val results = (1..5).map { async { bounded.preview(network("电影.mkv")) } }.awaitAll()

        assertEquals(1, tracker.requests)
        assertTrue(results.all { it.image == null })
    }

    @Test
    fun `本地与网络共用一份容量为 1 的抽帧许可`() = runTest {
        val tracker = ConcurrentExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

        val previews = (1..6).map { index ->
            async {
                if (index % 2 == 0) {
                    bounded.preview(local("第$index 集.mkv"))
                } else {
                    bounded.preview(network("第$index 集.mkv"))
                }
            }
        }
        previews.awaitAll()

        // 同一时刻只有一路在解码：网络读取会话名额不改变解码串行，本地与网络共享同一个许可。
        assertEquals(1, tracker.maxConcurrent)
        assertEquals(6, tracker.requests)
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
    fun `解码超时作废会话，下一次请求重新建连`() = runTest {
        val extractor = DiscardRecordingExtractor(decodeDelayMillis = 60_000)
        val source = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )

        val first = source.visiblePreview(network("超时.mkv"), 0)

        assertNull(first.image)
        assertEquals("超时走作废收尾", 1, extractor.discarded)
        assertEquals("句柄仍要关闭", 1, extractor.closed)
        assertEquals(1, extractor.connections)

        // 超时放弃的会话已作废：下一次请求重新准备并新建连。
        extractor.decodeDelayMillis = 0
        val retry = source.visiblePreview(network("超时.mkv"), 0)

        assertNotNull(retry.image)
        assertEquals("下一次请求重新准备", 2, extractor.prepares)
        assertEquals("上一次的会话已作废，下一次新建连", 2, extractor.connections)
    }

    @Test
    fun `取消在途预览读取时走放弃收尾`() = runTest {
        val extractor = DiscardRecordingExtractor(decodeGate = CompletableDeferred())
        val source = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )

        val request = async { source.visiblePreview(network("取消.mkv"), 0) }
        runCurrent()
        assertEquals(1, extractor.prepares)

        request.cancelAndJoin()
        runCurrent()
        assertEquals("取消后交给放弃收尾", 1, extractor.discarded)
    }

    @Test
    fun `慢提取超时后归还许可`() = runTest {
        val tracker = SlowFileExtractor(slowDelayMillis = 60_000)
        val bounded = CachedMediaPreviewSource(
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val slow = async { bounded.preview(network("慢.mkv")) }
        runCurrent()
        assertEquals(1, tracker.requests.size)

        val fast = async { bounded.preview(network("快.mkv")) }
        runCurrent()
        // 许可只有一个：快速项排在超时中的慢项后面，尚未开始。
        assertEquals(1, tracker.requests.size)

        assertNull(slow.await().image)
        assertNotNull(fast.await().image)
        assertTrue(tracker.requests.contains("smb://nas/media/快.mkv"))
    }

    @Test
    fun `可见请求不抢占已发出的许可`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val prefetched = network("预取.mkv")
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(prefetched.location))
        val prefetch = async { bounded.prefetch(prefetched) }
        runCurrent()
        assertEquals(listOf(prefetched.location), tracker.started)

        val visible = async { bounded.preview(network("可见.mkv")) }
        runCurrent()
        // 已发出的许可不抢占：可见项等这一帧结束，不打断在途抽取。
        assertEquals(listOf(prefetched.location), tracker.started)

        tracker.imageGate?.complete(Unit)
        assertNotNull(visible.await().image)
        runCatching { prefetch.await() }
        assertEquals(2, tracker.started.size)
    }

    @Test
    fun `可见请求取消同键预取并自己重抽`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val file = network("同键.mkv")
        bounded.updatePreviewWindow(
            visibleLocations = emptySet(),
            prefetchLocations = setOf(file.location),
        )
        val prefetch = async { bounded.prefetch(file) }
        runCurrent()
        assertEquals(1, tracker.started.count { location -> location == file.location })

        val visible = async { bounded.preview(file) }
        runCurrent()

        // 可见请求不跟预取一起等慢源：取消预取并按可见优先级重抽。
        assertEquals(2, tracker.started.count { location -> location == file.location })
        tracker.imageGate?.complete(Unit)
        assertNotNull(visible.await().image)
        runCatching { prefetch.await() }
    }

    @Test
    fun `可见项按列表索引取得许可`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blocker = async { bounded.preview(network("占位.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        // 乱序到达：许可只有一个，取得许可的顺序仍按列表索引。
        val ordered = listOf(5, 3, 1).map { index ->
            val file = network("第$index 集.mkv")
            async { bounded.visiblePreview(file, index) }
        }
        runCurrent()
        assertEquals(1, tracker.started.size)

        tracker.imageGate?.complete(Unit)
        blocker.await()
        ordered.awaitAll()

        assertEquals(
            listOf("第1 集.mkv", "第3 集.mkv", "第5 集.mkv"),
            tracker.started.drop(1).map { location -> location.substringAfterLast('/') },
        )
    }

    @Test
    fun `生产时钟与有效期同为毫秒`() {
        val first = systemMonotonicMillis()
        Thread.sleep(20)
        val elapsed = systemMonotonicMillis() - first

        assertTrue("毫秒时钟应只增加几十毫秒，实际增加 $elapsed", elapsed in 1..10_000)
    }

    @Test
    fun `网络无图结果仅本次运行内记忆`() = runTest {
        extractor.imageBytes = null
        val file = network("无图.mkv")

        assertNull(source.preview(file).image)
        assertNull(source.preview(file).image)
        assertEquals(1, extractor.imageRequests.size)
        assertTrue(directory.listFiles().isNullOrEmpty())

        // 新实例相当于重启：无图结果不被持久化，重新确认。
        val restarted = FakeMediaPreviewExtractor(imageBytes = byteArrayOf(1))
        val afterRestart = CachedMediaPreviewSource(
            extractor = restarted,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        assertNotNull(afterRestart.preview(file).image)
        assertEquals(1, restarted.imageRequests.size)
    }

    @Test
    fun `失败结果仅本次运行内记忆，重启后重新确认`() = runTest {
        extractor.imageFailure = MediaSourceException(MediaFailure.Unreachable, "连不上")
        val file = network("失败.mkv")

        assertNull(source.preview(file).image)
        assertTrue(directory.listFiles().isNullOrEmpty())

        val restarted = FakeMediaPreviewExtractor(imageBytes = byteArrayOf(1))
        val afterRestart = CachedMediaPreviewSource(
            extractor = restarted,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        assertNotNull(afterRestart.preview(file).image)
    }

    @Test
    fun `成功封面跨启动复用`() = runTest {
        extractor.imageBytes = byteArrayOf(1, 2, 3)
        val file = network("封面.mkv")
        assertNotNull(source.preview(file).image)

        val restarted = FakeMediaPreviewExtractor(imageBytes = byteArrayOf(9))
        val afterRestart = CachedMediaPreviewSource(
            extractor = restarted,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )

        assertArrayEquals(byteArrayOf(1, 2, 3), afterRestart.preview(file).image!!.readBytes())
        assertTrue(restarted.imageRequests.isEmpty())
    }

    @Test
    fun `本地无图结果重启后重新确认`() = runTest {
        extractor.imageBytes = null
        extractor.durationMillis = 187_000
        val file = local("歌.mp3", lastModified = 1_000)
        assertNull(source.preview(file).image)

        val restarted = FakeMediaPreviewExtractor(imageBytes = byteArrayOf(4), durationMillis = 187_000)
        val afterRestart = CachedMediaPreviewSource(
            extractor = restarted,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )

        assertNotNull(afterRestart.preview(file).image)
        assertEquals(1, restarted.imageRequests.size)
    }

    @Test
    fun `取消在途网络抽取后快速归还许可`() = runTest {
        val tracker = CancellableExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val first = async { bounded.preview(network("取消1.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        // 许可只有一个：第二个请求排队。
        val second = async { bounded.preview(network("取消2.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        first.cancelAndJoin()
        runCurrent()

        // 取消落到底层后许可归还，排队的下一个请求立即开始抽取。
        assertEquals(2, tracker.started.size)
        assertEquals("smb://nas/media/取消2.mkv", tracker.started.last())
        second.cancelAndJoin()
    }

    @Test
    fun `范围变化后排队中的预取取得许可时直接放弃`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        // 慢请求占着唯一许可，后面的预取只能排队。
        val blocker = async { bounded.preview(network("占位.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        val oldVisible = network("旧1.mkv")
        val oldPrefetch = network("旧2.mkv")
        bounded.updatePreviewWindow(
            visibleLocations = setOf(oldVisible.location),
            prefetchLocations = setOf(oldPrefetch.location),
        )
        val stale = listOf(oldVisible, oldPrefetch).map { file -> async { bounded.prefetch(file) } }
        runCurrent()
        assertEquals(1, tracker.started.size)

        // 滚到远处：旧范围不再可见也不再预取。
        bounded.updatePreviewWindow(
            visibleLocations = setOf("smb://nas/media/新.mkv"),
            prefetchLocations = emptySet(),
        )
        tracker.imageGate?.complete(Unit)
        val results = stale.awaitAll()
        blocker.await()

        // 旧请求取得许可后复查范围，直接放弃，没有进入抽取。
        assertEquals(1, tracker.started.size)
        assertTrue(results.all { preview -> preview.image == null && preview.durationMillis == null })
        assertEquals(1, imageFileCount())

        // 放弃不计失败退避：重新纳入范围后立即抽取。
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(oldVisible.location))
        assertNotNull(bounded.prefetch(oldVisible).image)
        assertEquals(2, tracker.started.size)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `放弃结果不共享，同键可见等待者重新抽取`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blocker = async { bounded.preview(network("占位.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        val file = network("同键.mkv")
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))
        // 离屏预取作 leader，同键可见请求挂在同一次抽取上等待。
        val leader = async { bounded.prefetch(file) }
        runCurrent()
        val waiter = async { bounded.preview(file) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        tracker.imageGate?.complete(Unit)
        // 可见请求取消同键预取；预取以取消收尾，可见请求自己抽取一次。
        runCatching { leader.await() }
        val waiterPreview = waiter.await()
        blocker.await()

        assertNotNull(waiterPreview.image)
        assertEquals(1, tracker.started.count { it == file.location })
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `命中补读时长与出图解耦，同键并发命中只补读一次`() = runTest {
        val tracker = GatedExtractor(durationFailure = IllegalStateException("无时长"))
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val file = local("同键时长.mkv")
        tracker.imageGate = null
        // 首次抽取读时长失败：落图并记录时长未知。
        assertNotNull(bounded.preview(file).image)
        assertEquals(1, tracker.durationRequests.count { it == file.location })

        tracker.durationFailure = null
        tracker.durationMillis = 500L
        val hits = (1..5).map { async { bounded.preview(file) } }.awaitAll()

        assertTrue("命中先出图", hits.all { preview -> preview.image != null })
        assertEquals("同键只补读一次", 2, tracker.durationRequests.count { it == file.location })
        assertEquals(500L, bounded.preview(file).durationMillis)
    }

    @Test
    fun `可见项先于预取项取得许可`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blocker = async { bounded.preview(network("占位.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        val visible = network("可见.mkv")
        val prefetches = listOf(network("预取1.mkv"), network("预取2.mkv"))
        bounded.updatePreviewWindow(
            visibleLocations = setOf(visible.location),
            prefetchLocations = prefetches.mapTo(mutableSetOf(), MediaFile::location),
        )
        val prefetched = prefetches.map { file -> async { bounded.prefetch(file) } }
        runCurrent()
        val shown = async { bounded.preview(visible) }
        runCurrent()

        tracker.imageGate?.complete(Unit)
        shown.await()
        prefetched.forEach { job -> runCatching { job.await() } }
        blocker.await()

        // 可见请求插队到排队中的预取之前，预取随后按等待顺序抽取。
        assertEquals(1, tracker.started.indexOf(visible.location))
        assertEquals(
            prefetches.map(MediaFile::location),
            tracker.started.drop(2),
        )
    }

    @Test
    fun `可见请求不因范围变化被放弃`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blocker = async { bounded.preview(network("占位.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        val visible = network("可见.mkv")
        bounded.updatePreviewWindow(visibleLocations = setOf(visible.location), prefetchLocations = emptySet())
        val shown = async { bounded.preview(visible) }
        runCurrent()
        // 可见卡片不会被重试：范围变化后仍须照常抽取。
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        tracker.imageGate?.complete(Unit)

        assertNotNull(shown.await().image)
        blocker.await()
        assertTrue(tracker.started.contains(visible.location))
    }

    @Test
    fun `本地预取离开范围后取得许可不抽取`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blocker = async { bounded.preview(local("占位.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        val staleFile = local("旧.mkv")
        bounded.updatePreviewWindow(
            visibleLocations = emptySet(),
            prefetchLocations = setOf(staleFile.location),
        )
        val stale = async { bounded.prefetch(staleFile) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        tracker.imageGate?.complete(Unit)
        val result = stale.await()
        blocker.await()

        assertEquals(1, tracker.started.size)
        assertNull(result.image)
        assertEquals(1, imageFileCount())
    }

    @Test
    fun `本地预取离开范围后取得许可不补读时长`() = runTest {
        val tracker = GatedExtractor(durationFailure = IllegalStateException("无时长"))
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val cached = local("旧.mkv")
        // 先落一张图为缓存，时长读取失败，记录为“时长未知”。
        tracker.imageGate = null
        assertNotNull(bounded.preview(cached).image)
        assertEquals(1, tracker.durationRequests.count { it == cached.location })

        tracker.imageGate = CompletableDeferred()
        val blocker = async { bounded.preview(local("占位.mkv")) }
        runCurrent()
        // 1 次预热抽取 + 1 个占着唯一许可的请求。
        assertEquals(2, tracker.started.size)

        bounded.updatePreviewWindow(
            visibleLocations = emptySet(),
            prefetchLocations = setOf(cached.location),
        )
        val stale = async { bounded.prefetch(cached) }
        runCurrent()
        assertEquals(1, tracker.durationRequests.count { it == cached.location })

        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        tracker.imageGate?.complete(Unit)
        assertNotNull(stale.await().image)
        blocker.await()

        // 放弃后没有补读时长，也没有记退避。
        assertEquals(1, tracker.durationRequests.count { it == cached.location })

        bounded.updatePreviewWindow(visibleLocations = setOf(cached.location), prefetchLocations = emptySet())
        assertNotNull(bounded.preview(cached).image)
        assertEquals(2, tracker.durationRequests.count { it == cached.location })
    }

    @Test
    fun `可见请求先于排队的预取取得许可`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blocker = async { bounded.preview(network("占位.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        val queuedFile = network("预取.mkv")
        val laterFile = network("后预取.mkv")
        bounded.updatePreviewWindow(
            visibleLocations = emptySet(),
            prefetchLocations = setOf(queuedFile.location, laterFile.location),
        )
        val queued = async { bounded.prefetch(queuedFile) }
        runCurrent()
        queued.cancel()
        queued.join()

        // 新预取先排队，可见项后到；两者都在等待许可，尚未抽取。
        val later = async { bounded.prefetch(laterFile) }
        runCurrent()
        val visible = async { bounded.preview(network("可见.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        tracker.imageGate?.complete(Unit)
        assertNotNull(visible.await().image)
        runCatching { later.await() }
        blocker.await()

        // 可见请求插队到排队中的预取之前。
        assertEquals(1, tracker.started.indexOf("smb://nas/media/可见.mkv"))
        assertEquals(2, tracker.started.indexOf(laterFile.location))
        assertEquals(1, tracker.maxConcurrent)
        assertFalse(tracker.started.contains(queuedFile.location))
    }

    @Test
    fun `预取失败不记无图，回到可见后重新抽取`() = runTest {
        extractor.imageBytes = null
        val file = network("预取无图.mkv")
        source.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))

        assertNull(source.prefetch(file).image)
        assertEquals(1, extractor.imageRequests.size)

        // 回到可见：预取结果没有记成“本次运行无图”，按可见请求重新抽取。
        extractor.imageBytes = byteArrayOf(1)
        source.updatePreviewWindow(visibleLocations = setOf(file.location), prefetchLocations = emptySet())
        assertNotNull(source.visiblePreview(file, 0).image)
        assertEquals(2, extractor.imageRequests.size)
    }

    @Test
    fun `预取失败后再次预取不再打开会话或解码`() = runTest {
        var prepares = 0
        var decodes = 0
        val counting = object : MediaPreviewExtractor {
            override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? =
                error("网络取图应走准备路径")

            override suspend fun durationMillis(file: MediaFile): Long? = null

            override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation {
                prepares++
                return NetworkPreviewPreparation(
                    decodeBlock = {
                        decodes++
                        null
                    },
                    closeBlock = {},
                )
            }
        }
        val bounded = CachedMediaPreviewSource(
            extractor = counting,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val file = network("预取失败.mkv")
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))

        assertNull(bounded.prefetch(file).image)
        assertEquals(1, prepares)
        assertEquals(1, decodes)

        // 模拟滚动进出：窗口每次重新包含该条目都会再次触发预取，但已被抑制，不再打开会话或解码。
        repeat(3) {
            bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
            bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))
            assertNull(bounded.prefetch(file).image)
        }

        assertEquals("预取失败后不再重新打开会话", 1, prepares)
        assertEquals("预取失败后不再重新解码", 1, decodes)
    }

    @Test
    fun `预取失败后条目变为可见仍会重新准备与解码`() = runTest {
        var prepares = 0
        var decodes = 0
        val counting = object : MediaPreviewExtractor {
            override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? =
                error("网络取图应走准备路径")

            override suspend fun durationMillis(file: MediaFile): Long? = null

            override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation {
                prepares++
                return NetworkPreviewPreparation(
                    decodeBlock = {
                        decodes++
                        if (decodes == 1) null else byteArrayOf(1)
                    },
                    closeBlock = {},
                )
            }
        }
        val bounded = CachedMediaPreviewSource(
            extractor = counting,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val file = network("预取失败.mkv")
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))

        assertNull(bounded.prefetch(file).image)
        assertEquals(1, decodes)
        // 再次预取被抑制：不重复解码。
        assertNull(bounded.prefetch(file).image)
        assertEquals(1, decodes)

        // 变为可见：清掉抑制，重新准备与解码一次并出图。
        bounded.updatePreviewWindow(visibleLocations = setOf(file.location), prefetchLocations = emptySet())
        assertNotNull(bounded.visiblePreview(file, 0).image)
        assertEquals(2, prepares)
        assertEquals(2, decodes)
    }

    @Test
    fun `预取失败不写失败退避`() = runTest {
        extractor.imageFailure = MediaSourceException(MediaFailure.Unreachable, "连不上")
        val file = network("失败.mkv")
        source.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))

        assertNull(source.prefetch(file).image)

        // 预取失败不写退避：可见请求到来时立即重抽，不被 30 秒退避挡住。
        extractor.imageFailure = null
        extractor.imageBytes = byteArrayOf(1)
        assertNotNull(source.preview(file).image)
        assertEquals(2, extractor.imageRequests.size)
    }

    @Test
    fun `仍在范围内的预取照常抽取`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        val file = network("预取.mkv")
        source.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))

        val preview = source.prefetch(file)

        assertNotNull(preview.image)
        assertEquals(listOf(file.location), extractor.imageRequests)
    }

    @Test
    fun `子目录没有预览`() = runTest {
        val preview = source.preview(testDirectory("剧集", "root/剧集"))

        assertNull(preview.image)
        assertNull(preview.durationMillis)
        assertTrue(extractor.imageRequests.isEmpty())
    }

    @Test
    fun `命中缓存先出图，时长补读不阻塞出图`() = runTest {
        val tracker = GatedExtractor(durationGate = CompletableDeferred())
        val cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined)
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = cache,
            backgroundScope = unconfinedBackgroundScope,
        )
        val file = local("电影.mkv", lastModified = 1_000)
        cache.save(
            location = thumbnailCacheKey(file, ThumbnailPolicy(), THUMBNAIL_TARGET_WIDTH),
            bytes = byteArrayOf(1),
            durationKnown = false,
        )

        // 时长补读被闸门压住，图片仍然立刻返回。
        val preview = bounded.preview(file)

        assertNotNull(preview.image)
        assertNull("时长还在后台补读", preview.durationMillis)
        assertEquals(1, tracker.durationRequests.count { it == file.location })

        tracker.durationGate?.complete(Unit)
        runCurrent()
    }

    @Test
    fun `补读时长不排到可见请求前面`() = runTest {
        val tracker = GatedExtractor()
        val cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined)
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = cache,
            backgroundScope = unconfinedBackgroundScope,
        )
        val hit = local("已出图.mkv", lastModified = 1_000)
        cache.save(
            location = thumbnailCacheKey(hit, ThumbnailPolicy(), THUMBNAIL_TARGET_WIDTH),
            bytes = byteArrayOf(1),
            durationKnown = false,
        )

        // 唯一许可被占住，命中补读与可见请求都只能排队。
        val blocker = async { bounded.preview(network("占位.mkv")) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        val shown = bounded.preview(hit)
        assertNotNull("命中缓存立即出图", shown.image)

        val visible = async { bounded.visiblePreview(network("可见.mkv"), 1) }
        runCurrent()

        tracker.imageGate?.complete(Unit)
        assertNotNull(visible.await().image)
        blocker.await()
        runCurrent()

        // 许可按列表索引发给可见请求，补读时长排在它之后：断言取许可的实际先后，不只看抽图。
        assertEquals(
            listOf("image:占位.mkv", "image:可见.mkv", "duration:已出图.mkv"),
            tracker.events,
        )
    }

    @Test
    fun `后到的低索引仍先于已排队的高索引出图`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val entries = (0 until 8).map { index -> network("第$index 集.mkv") }

        // 高索引先发起：它入队后停在发放之前，低索引这时才发起。
        val high = async(start = CoroutineStart.UNDISPATCHED) { bounded.visiblePreview(entries[7], 7) }
        val low = async(start = CoroutineStart.UNDISPATCHED) { bounded.visiblePreview(entries[0], 0) }
        runCurrent()

        // 发放从整队取最小 rank：先出图的是后到的低索引。
        assertEquals(listOf("第0 集.mkv"), tracker.started.map { it.substringAfterLast('/') })

        tracker.imageGate?.complete(Unit)
        high.await()
        low.await()
        assertEquals(
            listOf("第0 集.mkv", "第7 集.mkv"),
            tracker.started.map { it.substringAfterLast('/') },
        )
    }

    @Test
    fun `缓存命中不取消同键预取`() = runTest {
        // 预取还在解码、调用方等待时，同键缓存被填上：命中直接出图，不动在途预取。
        val tracker = GatedExtractor()
        val cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined)
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = cache,
            backgroundScope = unconfinedBackgroundScope,
        )
        val file = network("同键.mkv")
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))
        val prefetch = async { bounded.prefetch(file) }
        runCurrent()
        assertEquals(1, tracker.started.size)

        cache.save(
            location = thumbnailCacheKey(file, ThumbnailPolicy(), NETWORK_TARGET_WIDTH),
            bytes = byteArrayOf(9),
        )

        val shown = bounded.preview(file)

        assertArrayEquals(byteArrayOf(9), shown.image!!.readBytes())
        assertTrue("命中不取消同键预取", tracker.cancelled.isEmpty())
        assertEquals(1, tracker.started.size)

        tracker.imageGate?.complete(Unit)
        runCatching { prefetch.await() }
    }

    private fun imageFileCount(): Int =
        directory.listFiles().orEmpty().count { file -> file.name.endsWith(".img") }

    private fun metadataFile(): File =
        directory.listFiles().orEmpty().first { file -> file.name.endsWith(".meta") }

    @Test
    fun `预读在解码许可外进行，解码期间下一项的预读已经推进`() = runTest {
        val decoding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val prefetched = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val extractor = object : MediaPreviewExtractor {
            override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? =
                error("网络取图应走准备路径")

            override suspend fun durationMillis(file: MediaFile): Long? = null

            override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation {
                order += "预读:${file.name}"
                if (file.name.startsWith("b")) prefetched.complete(Unit)
                return NetworkPreviewPreparation(
                    decodeBlock = { _ ->
                        order += "解码:${file.name}"
                        if (file.name.startsWith("a")) {
                            decoding.complete(Unit)
                            release.await()
                        }
                        ByteArray(4)
                    },
                    closeBlock = {},
                )
            }
        }
        val source = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )

        val first = async { source.visiblePreview(network("a.mkv"), 0) }
        decoding.await()
        val second = async { source.visiblePreview(network("b.mkv"), 1) }
        withTimeout(5_000) { prefetched.await() }

        assertFalse("首项仍在解码", first.isCompleted)
        assertEquals("预读不占解码许可", listOf("预读:a.mkv", "解码:a.mkv", "预读:b.mkv"), order)

        release.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `本地抽取失败最多重试三次`() = runTest {
        extractor.imageFailure = IOException("读取失败")
        val file = local("坏文件.mkv")

        // 首发失败 + 三次重试都交给调度器；之后按占位图收尾。
        repeat(3) { attempt ->
            assertTrue("第 ${attempt + 1} 次失败要重试", source.preview(file).retryable)
        }
        assertFalse("重试用尽后保持占位图", source.preview(file).retryable)
        assertEquals(4, extractor.imageRequests.size)
        assertTrue("抽取失败不写无图缓存", directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `只读缓存取封面不触发抽取`() = runTest {
        extractor.imageBytes = byteArrayOf(1, 2, 3)
        val file = local("歌.mp3")

        assertNull("没有缓存时返回空，且不抽取", source.cachedImage(file))
        assertEquals(0, extractor.imageRequests.size)

        source.preview(file)
        assertEquals(1, extractor.imageRequests.size)

        val cached = source.cachedImage(file)
        assertNotNull(cached)
        assertArrayEquals(byteArrayOf(1, 2, 3), cached!!.readBytes())
        assertEquals("只读缓存不重新抽取", 1, extractor.imageRequests.size)
    }

    @Test
    fun `离屏预取不开句柄`() = runTest {
        var prepares = 0
        val extractor = object : MediaPreviewExtractor {
            override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? = ByteArray(1)

            override suspend fun durationMillis(file: MediaFile): Long? = null

            override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation? {
                prepares++
                return NetworkPreviewPreparation({ ByteArray(1) }, {})
            }
        }
        val source = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        source.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())

        assertNull(source.prefetch(network("离屏.mkv")).image)
        assertEquals("离屏预取不该打开句柄", 0, prepares)
    }

    @Test
    fun `打开挂起超过超时时间按失败收尾`() = runTest {
        val extractor = object : MediaPreviewExtractor {
            override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? = null

            override suspend fun durationMillis(file: MediaFile): Long? = null

            override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation? =
                awaitCancellation()
        }
        val source = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )

        val request = async { source.visiblePreview(network("打开挂起.mkv"), 0) }
        advanceUntilIdle()

        assertTrue("打开挂起要按失败收尾，不能永久占位", request.await().retryable)
    }

    @Test
    fun `准备返回空时按无图处理，不再新开句柄取图`() = runTest {
        var imageCalls = 0
        val extractor = object : MediaPreviewExtractor {
            override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
                imageCalls++
                return byteArrayOf(1)
            }

            override suspend fun durationMillis(file: MediaFile): Long? = null

            override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation? = null
        }
        val source = CachedMediaPreviewSource(
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )

        val preview = source.visiblePreview(network("没有会话.mkv"), 0)

        assertNull(preview.image)
        assertEquals("准备为空时不得另开句柄取图", 0, imageCalls)
        assertTrue("无图不落缓存", directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `可见项优先取得句柄名额，预取占不满`() = runTest {
        val slotExtractor = PreparedSlotExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = slotExtractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val prefetched = (0 until 6).map { index -> network("预取$index.mkv") }
        bounded.updatePreviewWindow(
            visibleLocations = emptySet(),
            prefetchLocations = prefetched.map { file -> file.location }.toSet(),
        )
        val prefetches = prefetched.map { file -> async { bounded.prefetch(file) } }
        runCurrent()
        assertEquals("预取最多占 2 个名额，留 2 个给可见项", 2, slotExtractor.started.size)

        val visible = async { bounded.visiblePreview(network("可见.mkv"), 9) }
        runCurrent()
        assertEquals("可见项不用等预取释放名额", 3, slotExtractor.started.size)
        assertTrue(slotExtractor.started.contains("smb://nas/media/可见.mkv"))

        slotExtractor.gate.complete(Unit)
        assertNotNull(visible.await().image)
        prefetches.forEach { it.await() }
        assertEquals("每个已开句柄都关闭一次", slotExtractor.started.size, slotExtractor.closed.size)
    }

    @Test
    fun `打开超时后归还句柄名额，后面的可见项继续打开`() = runTest {
        val slotExtractor = PreparedSlotExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = slotExtractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val blocked = (0 until 4).map { index ->
            async { bounded.visiblePreview(network("卡住$index.mkv"), index) }
        }
        runCurrent()
        assertEquals("四个名额同时被占住", 4, slotExtractor.started.size)

        // 打开一直不返回：超时只停止等待，名额回到池里。
        advanceTimeBy(15_000)
        runCurrent()
        blocked.forEach { request -> assertTrue("打开超时按失败收尾", request.await().retryable) }

        val later = async { bounded.visiblePreview(network("后来的.mkv"), 9) }
        runCurrent()
        assertEquals("超时后名额可以再发出去", 5, slotExtractor.started.size)
        assertTrue(slotExtractor.started.contains("smb://nas/media/后来的.mkv"))
    }

    @Test
    fun `取消可见请求后归还句柄名额，已开的句柄全部关闭`() = runTest {
        val slotExtractor = PreparedSlotExtractor()
        slotExtractor.gate.complete(Unit)
        slotExtractor.decodeGate = CompletableDeferred()
        val bounded = CachedMediaPreviewSource(
            extractor = slotExtractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val blocked = (0 until 4).map { index ->
            async { bounded.visiblePreview(network("占位$index.mkv"), index) }
        }
        runCurrent()
        assertEquals("四个名额全部占住", 4, slotExtractor.started.size)

        blocked.drop(1).forEach { request -> request.cancel() }
        runCurrent()
        assertEquals("取消的请求各自关闭句柄", 3, slotExtractor.closed.size)

        val later = async { bounded.visiblePreview(network("后来的.mkv"), 9) }
        runCurrent()
        assertEquals("名额归还后才能再开", 5, slotExtractor.started.size)

        slotExtractor.decodeGate.complete(Unit)
        runCurrent()
        assertNotNull(blocked[0].await().image)
        assertNotNull(later.await().image)
    }

    @Test
    fun `打开超时放弃后迟到的句柄由打开方关闭一次`() = runTest {
        val late = UninterruptibleOpenExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = late,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            backgroundScope = unconfinedBackgroundScope,
        )
        val request = async { bounded.visiblePreview(network("迟到.mkv"), 0) }
        runCurrent()
        assertEquals(1, late.started.size)

        // 打开一直不返回：超时只停止等待，请求按失败收尾。
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(request.await().retryable)

        // 迟到的打开终于返回：没人接手，由打开方作废并关闭，恰好一次。
        late.gate.complete(Unit)
        runCurrent()
        assertEquals("迟到的句柄要关一次", 1, late.closed.size)
        assertEquals("迟到的会话要作废", 1, late.discarded.size)
        assertEquals(1, late.connections)

        // 打开超时作废了会话：下一次准备必须新建会话。
        val retry = async { bounded.visiblePreview(network("迟到.mkv"), 0) }
        runCurrent()
        assertNotNull(retry.await().image)
        assertEquals("打开超时后下一次准备新建连", 2, late.connections)
    }

    private fun local(name: String, size: Long = 0, lastModified: Long = 0) =
        MediaFile(name = name, location = "root/$name", size = size, lastModified = lastModified)

    private fun network(name: String, lastModified: Long = 1_700_000_000_000) = networkAt(name, lastModified)

    private fun networkAt(name: String, lastModified: Long) = MediaFile(
        name = name,
        location = "smb://nas/media/$name",
        lastModified = lastModified,
        source = MediaSource.Network,
    )

    private fun cacheKey(file: MediaFile, policy: ThumbnailPolicy = ThumbnailPolicy()) =
        thumbnailCacheKey(file, policy, previewTargetWidth(file, policy))
}

/** 测试用的后台范围：不受限调度，命中后的时长补读在断言前跑完。 */
private val unconfinedBackgroundScope = CoroutineScope(Dispatchers.Unconfined)

/** 测试用准备：解码时才调用 [imageBytes]，不新开真实句柄。 */
private fun MediaPreviewExtractor.preparation(file: MediaFile): NetworkPreviewPreparation =
    NetworkPreviewPreparation(
        decodeBlock = { targetWidth -> imageBytes(file, targetWidth) },
        closeBlock = {},
    )

/** 抽取开始后等待闸门，用来稳定构造唯一许可被占用、后续请求排队的场景。 */
private class GatedExtractor(
    var imageGate: CompletableDeferred<Unit>? = CompletableDeferred(),
    var durationGate: CompletableDeferred<Unit>? = null,
    var durationFailure: Exception? = null,
    var durationMillis: Long? = null,
) : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    val started = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val durationRequests = mutableListOf<String>()

    /** 按取到许可后的实际发生顺序记录读取：抽图与补读时长混在一条序列里。 */
    val events = mutableListOf<String>()
    var concurrent = 0
    var maxConcurrent = 0

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        started += file.location
        events += "image:${file.location.substringAfterLast('/')}"
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        try {
            imageGate?.await()
        } catch (cancellation: CancellationException) {
            cancelled += file.location
            throw cancellation
        } finally {
            concurrent--
        }
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? {
        durationRequests += file.location
        events += "duration:${file.location.substringAfterLast('/')}"
        durationGate?.await()
        durationFailure?.let { throw it }
        return durationMillis
    }
}

/** 名字以“慢”开头的条目会先等一段时间，用来验证超时后的许可释放。 */
private class SlowFileExtractor(private val slowDelayMillis: Long) : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    val requests = mutableListOf<String>()

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        requests += file.location
        if (file.name.startsWith("慢")) delay(slowDelayMillis)
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 记录同时进行的抽图数，用来验证并发上限；可注入失败。 */
private class ConcurrentExtractor(private val failure: Exception? = null) : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    var requests = 0
    var concurrent = 0
    var maxConcurrent = 0

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
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

/** 第一次抽取会一直等待，用来验证首个调用者取消后等待者重新抽取。 */
private class RetryExtractor : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    var requests = 0

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        requests++
        if (requests == 1) delay(60_000)
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 第一次抽取抛出 Error，用来验证等待者不会永久阻塞。 */
private class ErrorOnceExtractor : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    var requests = 0

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        requests++
        if (requests == 1) {
            yield()
            throw OutOfMemoryError("模拟解码失败")
        }
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 直接读本地文件，文件不存在或不可读时由真实 IO 抛错，用来验证未经注入的失败路径。 */
private class FileReadingExtractor : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? =
        File(file.location).readBytes()

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 抽取失败直到 [failure] 被置空，用来验证整次抽取的退避与恢复。 */
private class RetryAfterFailureExtractor : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    var requests = 0
    var failure: Exception? = IllegalStateException("抽取失败")

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        requests++
        failure?.let { throw it }
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 网络候选帧恒为纯色，用来验证不合格时保持占位图。 */
private class SolidNetworkPreviewExtractor : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    var decodes = 0

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? =
        extractNetworkFrame(
            durationMillis = 90_000,
            decode = {
                decodes++
                byteArrayOf(1)
            },
            isSolid = { true },
        )

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 打开句柄先等闸门；记录已开与已关的句柄，用来验证句柄名额的容量与归还。 */
private class PreparedSlotExtractor : MediaPreviewExtractor {

    /** 打开句柄前等的闸门。 */
    val gate = CompletableDeferred<Unit>()

    /** 解码前等的闸门；默认已放行。 */
    var decodeGate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) }

    val started = mutableListOf<String>()
    val closed = mutableListOf<String>()

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? = byteArrayOf(1)

    override suspend fun durationMillis(file: MediaFile): Long? = null

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation {
        started += file.location
        gate.await()
        return NetworkPreviewPreparation(
            decodeBlock = {
                decodeGate.await()
                byteArrayOf(1)
            },
            closeBlock = { closed += file.location },
        )
    }
}

/** 打开不可取消：等待期间取消打不断它，迟到的句柄只能由打开方关闭。 */
private class UninterruptibleOpenExtractor : MediaPreviewExtractor {

    /** 迟到的打开在这里放行。 */
    val gate = CompletableDeferred<Unit>()

    val started = mutableListOf<String>()
    val closed = mutableListOf<String>()
    val discarded = mutableListOf<String>()

    /** 假会话的建连次数：作废后下一次准备要重新建连。 */
    var connections = 0
    private var live = false

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? = byteArrayOf(1)

    override suspend fun durationMillis(file: MediaFile): Long? = null

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation {
        started += file.location
        // 等待放在 NonCancellable 里：等价于取消打不断的阻塞打开。
        withContext(NonCancellable) { gate.await() }
        if (!live) {
            live = true
            connections++
        }
        return NetworkPreviewPreparation(
            decodeBlock = { byteArrayOf(1) },
            closeBlock = { closed += file.location },
            discardBlock = {
                discarded += file.location
                live = false
                closed += file.location
            },
        )
    }
}

/** 记录句柄的关闭与作废：超时、取消必须走作废，不能只关句柄。 */
private class DiscardRecordingExtractor(
    var decodeDelayMillis: Long = 0,
    private val decodeGate: CompletableDeferred<Unit>? = null,
) : MediaPreviewExtractor {

    var prepares = 0
    var connections = 0
    var closed = 0
    var discarded = 0

    /** 假会话是否还活着：作废后下一次准备要重新建连。 */
    private var live = false

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? = null

    override suspend fun durationMillis(file: MediaFile): Long? = null

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation {
        prepares++
        if (!live) {
            live = true
            connections++
        }
        return NetworkPreviewPreparation(
            decodeBlock = {
                val gate = decodeGate
                if (gate != null) gate.await() else delay(decodeDelayMillis)
                byteArrayOf(1)
            },
            closeBlock = { closed++ },
            discardBlock = {
                if (live) {
                    live = false
                    discarded++
                }
                closed++
            },
            abandonBlock = {
                if (live) {
                    live = false
                    discarded++
                }
                closed++
            },
        )
    }
}

/** 抽取一直挂起，取消后才结束，用来验证许可归还。 */
private class CancellableExtractor : MediaPreviewExtractor {

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation = preparation(file)

    val started = mutableListOf<String>()

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        started += file.location
        awaitCancellation()
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}
