package com.cc3301.ccplayer.library

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class CachedMediaPreviewSourceTest {

    private val directory: File = Files.createTempDirectory("media-preview").toFile()
    private val extractor = FakeMediaPreviewExtractor()
    private val source = CachedMediaPreviewSource(
        extractor = extractor,
        cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
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
    fun `网络单帧纯色时保持占位图`() = runTest {
        val solid = SolidNetworkPreviewExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = solid,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )

        val preview = bounded.preview(network("纯色.mkv"))

        assertNull(preview.image)
        assertEquals(1, solid.decodes)
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
    fun `旧格式单行元数据命中后重新读取时长并回写`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = 187_000
        val file = local("电影.mkv", lastModified = 1_000)
        source.preview(file)
        val key = cacheKey(file)
        metadataFile().writeText("1000")
        val callsAfterFirst = extractor.durationRequests.size

        val hit = source.preview(file)

        assertEquals(187_000L, hit.durationMillis)
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
    fun `无元数据的旧条目命中后重新读取时长并回写`() = runTest {
        extractor.imageBytes = byteArrayOf(1)
        extractor.durationMillis = 187_000
        val file = local("电影.mkv", lastModified = 1_000)
        source.preview(file)
        val key = cacheKey(file)
        metadataFile().delete()
        val callsAfterFirst = extractor.durationRequests.size

        val hit = source.preview(file)

        assertEquals(187_000L, hit.durationMillis)
        assertEquals(callsAfterFirst + 1, extractor.durationRequests.size)
        val written = cache().entry(key)
        assertNotNull(written)
        assertEquals(187_000L, written!!.durationMillis)
        assertTrue(written.durationKnown)

        source.preview(file)
        assertEquals(callsAfterFirst + 1, extractor.durationRequests.size)
    }

    @Test
    fun `抽取阶段读时长失败保留图片且不落失败标记`() = runTest {
        var now = 1_000L
        val cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined, clock = { now })
        val bounded = CachedMediaPreviewSource(
            extractor = extractor,
            cache = cache,
            monotonicClock = { now },
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
            monotonicClock = { now },
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
        val retried = bounded.preview(file)
        assertEquals(187_000L, retried.durationMillis)
        val written = cache.entry(key)
        assertNotNull(written)
        assertEquals(187_000L, written!!.durationMillis)
        assertTrue(written.durationKnown)
    }

    @Test
    fun `读时长失败后退避，退避期内不再重试`() = runTest {
        var now = 1_000L
        val cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined, clock = { now })
        val bounded = CachedMediaPreviewSource(
            extractor = extractor,
            cache = cache,
            monotonicClock = { now },
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
            monotonicClock = { now },
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
        assertEquals(187_000L, hit.durationMillis)
        val updated = cache().entry(key)
        assertNotNull(updated)
        assertEquals(187_000L, updated!!.durationMillis)
        assertTrue(updated.durationKnown)
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
            monotonicClock = { now },
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
    fun `整次抽取失败后按退避重试且成功后清理键`() = runTest {
        var now = 1_000L
        val tracker = RetryAfterFailureExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClock = { now },
        )
        val file = network("电影.mkv")

        assertNull(bounded.preview(file).image)
        assertEquals(1, tracker.requests)

        now += 29_999
        assertNull(bounded.preview(file).image)
        assertEquals(1, tracker.requests)

        now += 1
        assertNull(bounded.preview(file).image)
        assertEquals(2, tracker.requests)

        // 连续失败后间隔翻倍。
        now += 59_999
        assertNull(bounded.preview(file).image)
        assertEquals(2, tracker.requests)

        now += 1
        tracker.failure = null
        assertNotNull(bounded.preview(file).image)
        assertEquals(3, tracker.requests)

        // 成功后的键已清理，再次请求直接命中缓存。
        assertNotNull(bounded.preview(file).image)
        assertEquals(3, tracker.requests)
    }

    @Test
    fun `整次抽取失败退避间隔倍增且有上限`() = runTest {
        var now = 1_000L
        val tracker = RetryAfterFailureExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClock = { now },
        )
        val file = network("上限.mkv")

        assertNull(bounded.preview(file).image)
        assertEquals(1, tracker.requests)

        val delays = listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L)
        delays.forEachIndexed { index, delay ->
            now += delay - 1
            assertNull(bounded.preview(file).image)
            assertEquals(index + 1, tracker.requests)
            now += 1
            assertNull(bounded.preview(file).image)
            assertEquals(index + 2, tracker.requests)
        }

        // 上限为十分钟：不足十分钟不重试，满十分钟才重试。
        now += 599_999
        assertNull(bounded.preview(file).image)
        assertEquals(7, tracker.requests)
        now += 1
        assertNull(bounded.preview(file).image)
        assertEquals(8, tracker.requests)
    }

    @Test
    fun `退避记录满上限时淘汰最早到期键`() = runTest {
        var now = 1_000L
        val tracker = PerKeyFailureExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClock = { now },
        )
        val entries = (1..513).map { index ->
            local("第$index 集.mkv", lastModified = index.toLong())
        }
        entries.forEach { file ->
            now += 1
            assertNull(bounded.preview(file).image)
        }

        // 第 1 个键到期最早，第 513 次失败时被淘汰；第 2 个键仍在退避中。
        val earliest = entries.first()
        val retained = entries[1]
        assertNull(bounded.preview(retained).image)
        assertEquals(1, tracker.requests.getValue(retained.location))

        assertNull(bounded.preview(earliest).image)
        assertEquals(2, tracker.requests.getValue(earliest.location))
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
                monotonicClock = { now },
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

        val preview = CachedMediaPreviewSource(extractor = extractor, cache = cache).preview(file)

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
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
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
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
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
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val job = launch { bounded.preview(network("慢.mkv")) }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `本地取消慢提取后不写入缓存`() = runTest {
        val tracker = SlowFileExtractor(slowDelayMillis = 60_000)
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val job = launch { bounded.preview(local("慢.mkv")) }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `取消后同键可重新抽取`() = runTest {
        val tracker = RetryExtractor()
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
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
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

        val previews = (1..5).map { async { bounded.preview(network("电影.mkv")) } }
        val results = previews.awaitAll()

        assertEquals(1, tracker.requests)
        assertTrue(results.all { it.image != null })
    }

    @Test
    fun `同一媒体并发请求失败时也只抽取一次`() = runTest {
        val tracker = ConcurrentExtractor(failure = MediaSourceException(MediaFailure.Unreachable, "连不上"))
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

        val results = (1..5).map { async { bounded.preview(network("电影.mkv")) } }.awaitAll()

        assertEquals(1, tracker.requests)
        assertTrue(results.all { it.image == null })
    }

    @Test
    fun `本地并发提取不超过 2`() = runTest {
        val tracker = ConcurrentExtractor()
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

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
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
        val slow = (1..3).map { index -> async { bounded.preview(network("慢$index.mkv")) } }
        runCurrent()
        assertEquals(2, tracker.requests.size)

        val fast = async { bounded.preview(network("快.mkv")) }
        val fastPreview = fast.await()

        assertNotNull(fastPreview.image)
        assertTrue(tracker.requests.contains("smb://nas/media/快.mkv"))
        assertTrue(slow.awaitAll().all { it.image == null })
    }

    @Test
    fun `同一目录并发提取有上限`() = runTest {
        val tracker = ConcurrentExtractor()
        val bounded = CachedMediaPreviewSource(extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

        val previews = (1..10).map { index -> async { bounded.preview(network("第$index 集.mkv")) } }
        previews.awaitAll()

        assertTrue(tracker.maxConcurrent <= 2)
        assertEquals(10, tracker.requests)
    }

    @Test
    fun `范围变化后排队中的预取取得许可时直接放弃`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        // 两个慢请求占满网络许可，后面两个预取只能排队。
        val blockers = listOf("占位1", "占位2").map { name ->
            async { bounded.preview(network("$name.mkv")) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

        val oldVisible = network("旧1.mkv")
        val oldPrefetch = network("旧2.mkv")
        bounded.updatePreviewWindow(
            visibleLocations = setOf(oldVisible.location),
            prefetchLocations = setOf(oldPrefetch.location),
        )
        val stale = listOf(oldVisible, oldPrefetch).map { file -> async { bounded.prefetch(file) } }
        runCurrent()
        assertEquals(2, tracker.started.size)

        // 滚到远处：旧范围不再可见也不再预取。
        bounded.updatePreviewWindow(
            visibleLocations = setOf("smb://nas/media/新.mkv"),
            prefetchLocations = emptySet(),
        )
        tracker.gate.complete(Unit)
        val results = stale.awaitAll()
        blockers.awaitAll()

        // 旧请求取得许可后复查范围，直接放弃，没有进入抽取。
        assertEquals(2, tracker.started.size)
        assertTrue(results.all { preview -> preview.image == null && preview.durationMillis == null })
    }

    @Test
    fun `可见项先于预取项取得许可`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blockers = listOf("占位1", "占位2").map { name ->
            async { bounded.preview(network("$name.mkv")) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

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

        tracker.gate.complete(Unit)
        shown.await()
        prefetched.awaitAll()
        blockers.awaitAll()

        val visibleIndex = tracker.started.indexOf(visible.location)
        assertTrue(visibleIndex >= 0)
        assertTrue(visibleIndex < tracker.started.indexOf(prefetches[0].location))
        assertTrue(visibleIndex < tracker.started.indexOf(prefetches[1].location))
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

    private fun metadataFile(): File =
        directory.listFiles().orEmpty().first { file -> file.name.endsWith(".meta") }

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

/** 抽取开始后等待闸门，用来稳定构造并发名额被占满、后续请求排队的场景。 */
private class GatedExtractor : MediaPreviewExtractor {

    val started = mutableListOf<String>()
    val gate = CompletableDeferred<Unit>()

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        started += file.location
        gate.await()
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 名字以“慢”开头的条目会先等一段时间，用来验证超时后的并发名额释放。 */
private class SlowFileExtractor(private val slowDelayMillis: Long) : MediaPreviewExtractor {

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

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? =
        File(file.location).readBytes()

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 抽取失败直到 [failure] 被置空，用来验证整次抽取的退避与恢复。 */
private class RetryAfterFailureExtractor : MediaPreviewExtractor {

    var requests = 0
    var failure: Exception? = IllegalStateException("抽取失败")

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        requests++
        failure?.let { throw it }
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 网络单帧恒为纯色，用来验证不合格时保持占位图。 */
private class SolidNetworkPreviewExtractor : MediaPreviewExtractor {

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

/** 每个位置都抽取失败，并按位置记录请求次数，用来验证退避记录上限。 */
private class PerKeyFailureExtractor : MediaPreviewExtractor {

    val requests = mutableMapOf<String, Int>()

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        requests[file.location] = (requests[file.location] ?: 0) + 1
        throw IllegalStateException("抽取失败")
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}
