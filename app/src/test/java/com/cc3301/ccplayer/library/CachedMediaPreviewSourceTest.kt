package com.cc3301.ccplayer.library

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = cache,
            policy = ThumbnailPolicy(missingModifiedValidityMillis = 1_000),
        )
        val longLived = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = cache,
            monotonicClockMillis = { now },
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = cache,
            monotonicClockMillis = { now },
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = cache,
            monotonicClockMillis = { now },
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = cache,
            monotonicClockMillis = { now },
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = cache,
            monotonicClockMillis = { now },
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClockMillis = { now },
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClockMillis = { now },
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClockMillis = { now },
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(version = 1),
        )
        val second = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 320),
        )
        val second = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 100),
        )
        val minimum = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 320),
        )
        val second = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 512),
        )
        val narrow = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor, cache = cache).preview(file)

        assertArrayEquals(byteArrayOf(1), preview.image!!.readBytes())
        assertEquals(1, extractor.imageRequests.size)
    }

    @Test
    fun `抽取器收到钳制后的目标宽度`() = runTest {
        val narrow = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            policy = ThumbnailPolicy(targetWidth = 100),
        )
        val wide = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

        val results = (1..5).map { async { bounded.preview(network("电影.mkv")) } }.awaitAll()

        assertEquals(1, tracker.requests)
        assertTrue(results.all { it.image == null })
    }

    @Test
    fun `本地并发提取不超过 2`() = runTest {
        val tracker = ConcurrentExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

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
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))
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
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker, cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined))

        val previews = (1..10).map { index -> async { bounded.preview(network("第$index 集.mkv")) } }
        previews.awaitAll()

        assertTrue(tracker.maxConcurrent <= 2)
        assertEquals(10, tracker.requests)
    }

    @Test
    fun `网络每源并发不超过 2 且全局不超过 4`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val files = listOf(
            "smb://nas-a/media/第1 集.mkv",
            "smb://nas-a/media/第2 集.mkv",
            "smb://nas-a/media/第3 集.mkv",
            "smb://nas-b/media/第1 集.mkv",
            "smb://nas-b/media/第2 集.mkv",
            "smb://nas-b/media/第3 集.mkv",
        )

        val requests = files.map { location -> async { bounded.preview(fileAt(location)) } }
        runCurrent()

        assertEquals(4, tracker.started.size)
        val perSource = tracker.started.groupingBy(::networkSourceKey).eachCount()
        assertEquals(setOf("smb://nas-a/media", "smb://nas-b/media"), perSource.keys)
        assertTrue(perSource.values.all { count -> count <= 2 })

        tracker.imageGate?.complete(Unit)
        requests.awaitAll()
        assertTrue(tracker.maxConcurrent <= 4)
    }

    @Test
    fun `网络并发按连接源书签分组而不是按共享`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            sourceKeyOf = { location ->
                if (location.contains("/书签A/")) "bookmark-a" else "bookmark-b"
            },
        )
        val files = listOf(
            "smb://nas/media/书签A/第1 集.mkv",
            "smb://nas/media/书签A/第2 集.mkv",
            "smb://nas/media/书签A/第3 集.mkv",
            "smb://nas/media/书签B/第1 集.mkv",
            "smb://nas/media/书签B/第2 集.mkv",
            "smb://nas/media/书签B/第3 集.mkv",
        )

        val requests = files.map { location -> async { bounded.preview(fileAt(location)) } }
        runCurrent()

        // 两个书签共用同一台主机与共享，仍各自拿到 2 个名额。
        assertEquals(4, tracker.started.size)
        tracker.imageGate?.complete(Unit)
        requests.awaitAll()
    }

    @Test
    fun `预取解析连接源前已登记，可见请求可取消并取得名额`() = runTest {
        val tracker = GatedExtractor()
        val lookupStarted = CompletableDeferred<Unit>()
        val lookupGate = CompletableDeferred<Unit>()
        var lookups = 0
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            sourceKeyOf = {
                val call = lookups++
                if (call == 0) {
                    lookupStarted.complete(Unit)
                    lookupGate.await()
                }
                "bookmark"
            },
        )
        val prefetched = network("预取.mkv")
        bounded.updatePreviewWindow(emptySet(), setOf(prefetched.location))
        val prefetch = async { bounded.prefetch(prefetched) }
        runCurrent()

        // 预取停在解析连接源，尚未取得许可；但已经登记，可见请求能取消它。
        assertTrue("预取应先停在连接源解析", lookupStarted.isCompleted)
        assertTrue(tracker.started.isEmpty())

        val visible = network("可见.mkv")
        val visibleJob = async { bounded.preview(visible) }
        runCurrent()
        assertEquals(listOf(visible.location), tracker.started)

        lookupGate.complete(Unit)
        runCurrent()
        assertTrue("预取在取得许可前已被取消", runCatching { prefetch.await() }.isFailure)
        assertEquals("预取不得进入抽取", 1, tracker.started.size)

        tracker.imageGate?.complete(Unit)
        assertNotNull(visibleJob.await().image)
    }

    @Test
    fun `可见请求取消在途预取并占用其名额`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val prefetched = listOf("预取1.mkv", "预取2.mkv").map { name -> network(name) }
        bounded.updatePreviewWindow(
            visibleLocations = emptySet(),
            prefetchLocations = prefetched.mapTo(mutableSetOf(), MediaFile::location),
        )
        val prefetchJobs = prefetched.map { file -> async { bounded.prefetch(file) } }
        runCurrent()
        assertEquals(2, tracker.started.size)

        val visible = async { bounded.preview(network("可见.mkv")) }
        runCurrent()

        assertTrue(tracker.cancelled.containsAll(prefetched.map(MediaFile::location)))
        assertTrue(tracker.started.contains("smb://nas/media/可见.mkv"))

        tracker.imageGate?.complete(Unit)
        assertNotNull(visible.await().image)
        prefetchJobs.forEach { job -> runCatching { job.await() } }
    }

    @Test
    fun `可见请求取消同键预取并自己重抽`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
    fun `同一连接源内乱序到达仍按索引取得许可`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blockers = listOf("占位1.mkv", "占位2.mkv").map { name ->
            async { bounded.preview(network(name)) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

        val ordered = listOf(5, 3, 1).map { index ->
            val file = network("第$index 集.mkv")
            async { bounded.visiblePreview(file, index) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

        tracker.imageGate?.complete(Unit)
        blockers.awaitAll()
        ordered.awaitAll()

        assertEquals(
            listOf("第1 集.mkv", "第3 集.mkv", "第5 集.mkv"),
            tracker.started.drop(2).map { location -> location.substringAfterLast('/') },
        )
    }

    @Test
    fun `预取等待全局许可时也让出源内名额`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val sourceB = listOf("smb://b/media/第1 集.mkv", "smb://b/media/第2 集.mkv")
        val sourceC = listOf("smb://c/media/第1 集.mkv", "smb://c/media/第2 集.mkv")
        val sourceA = listOf("smb://a/media/第1 集.mkv", "smb://a/media/第2 集.mkv")
        bounded.updatePreviewWindow(
            visibleLocations = emptySet(),
            prefetchLocations = (sourceB + sourceC + sourceA).toSet(),
        )

        // 两个其它源占满全局 4 个名额。
        val globalBlockers = (sourceB + sourceC).map { location -> async { bounded.prefetch(fileAt(location)) } }
        runCurrent()
        assertEquals(4, tracker.started.size)

        // A 源预取拿到源内名额，但卡在全局许可上；此时尚未开始抽取。
        val waiting = sourceA.map { location -> async { bounded.prefetch(fileAt(location)) } }
        runCurrent()
        assertEquals(4, tracker.started.size)

        val visible = async { bounded.preview(fileAt("smb://a/media/可见.mkv")) }
        runCurrent()

        tracker.imageGate?.complete(Unit)
        assertNotNull(visible.await().image)
        (globalBlockers + waiting).forEach { job -> runCatching { job.await() } }

        // 等待全局许可的 A 源预取被取消，没有进入抽取。
        assertTrue(sourceA.none { location -> tracker.started.contains(location) })
    }

    @Test
    fun `连接源标识在 IO 线程查询并缓存`() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        var lookupThread: Thread? = null
        var now = 0L
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.IO,
            extractor = FakeMediaPreviewExtractor(imageFailure = IllegalStateException("失败")),
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClockMillis = { now },
            sourceKeyOf = {
                calls.incrementAndGet()
                lookupThread = Thread.currentThread()
                "bookmark"
            },
        )
        val file = network("缓存键.mkv")

        assertNull(bounded.preview(file).image)
        assertEquals(1, calls.get())
        assertNotNull(lookupThread)
        assertTrue(lookupThread !== Thread.currentThread())

        now += 30_000
        assertNull(bounded.preview(file).image)
        assertEquals(1, calls.get())
    }

    @Test
    fun `连接源标识缓存过期后重新解析`() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        var now = 0L
        var sourceKey = "bookmark-a"
        val extractor = FakeMediaPreviewExtractor(imageFailure = IllegalStateException("失败"))
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = extractor,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClockMillis = { now },
            sourceKeyOf = {
                calls.incrementAndGet()
                sourceKey
            },
        )
        val file = network("缓存键.mkv")

        assertNull(bounded.preview(file).image)
        assertEquals(1, calls.get())

        // 书签改址，且标识缓存与失败退避都过期：重新解析并使用新键。
        sourceKey = "bookmark-b"
        extractor.imageFailure = null
        extractor.imageBytes = byteArrayOf(1)
        now += 30_001
        assertNotNull(bounded.preview(file).image)
        assertEquals(2, calls.get())

        // 再次命中图片缓存时不再查询连接源。
        assertNotNull(bounded.preview(file).image)
        assertEquals(2, calls.get())
    }

    @Test
    fun `来源标识缓存三十秒内命中，超过后失效`() = runTest {
        var now = 0L
        var lookups = 0
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = FakeMediaPreviewExtractor(imageBytes = byteArrayOf(1)),
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClockMillis = { now },
            sourceKeyOf = {
                lookups++
                "bookmark"
            },
        )

        assertNotNull(bounded.preview(network("缓存键.mkv", lastModified = 1_000)).image)
        assertEquals(1, lookups)

        // 同一位置换一份缓存键，确保重新进入网络流程；30 秒内仍复用来源标识。
        now += 30_000
        assertNotNull(bounded.preview(network("缓存键.mkv", lastModified = 2_000)).image)
        assertEquals(1, lookups)

        // 超过有效期后重新解析来源标识。
        now += 1
        assertNotNull(bounded.preview(network("缓存键.mkv", lastModified = 3_000)).image)
        assertEquals(2, lookups)
    }

    @Test
    fun `生产时钟与有效期同为毫秒`() {
        val first = systemMonotonicMillis()
        Thread.sleep(20)
        val elapsed = systemMonotonicMillis() - first

        assertTrue("毫秒时钟应只增加几十毫秒，实际增加 $elapsed", elapsed in 1..10_000)
    }

    @Test
    fun `默认连接源键按主机与共享分组`() {
        assertEquals("smb://nas/media", networkSourceKey("smb://nas/media/剧集/第1 集.mkv"))
        assertEquals("smb://nas:1445/media", networkSourceKey("smb://nas:1445/media/电影.mkv"))
        assertEquals("https://dav.example.com", networkSourceKey("https://dav.example.com/a/b.mkv"))
    }

    @Test
    fun `单个慢源不阻塞本地与其它源`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        // 慢源占满自己的 2 个源内名额，但不应占满全局，也不应影响本地。
        val slow = (1..2).map { index ->
            async { bounded.preview(fileAt("smb://slow/media/第$index 集.mkv")) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

        val other = async { bounded.preview(fileAt("smb://fast/media/电影.mkv")) }
        val localFile = async { bounded.preview(local("本地.mkv")) }
        runCurrent()

        assertEquals(4, tracker.started.size)
        assertTrue(tracker.started.contains("smb://fast/media/电影.mkv"))
        assertTrue(tracker.started.contains("root/本地.mkv"))

        tracker.imageGate?.complete(Unit)
        (slow + listOf(other, localFile)).awaitAll()
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val first = async { bounded.preview(network("取消1.mkv")) }
        val second = async { bounded.preview(network("取消2.mkv")) }
        runCurrent()
        assertEquals(2, tracker.started.size)

        first.cancelAndJoin()
        runCurrent()
        val third = async { bounded.preview(network("继续.mkv")) }
        runCurrent()

        assertEquals(3, tracker.started.size)
        second.cancelAndJoin()
        third.cancelAndJoin()
    }

    @Test
    fun `范围变化后排队中的预取取得许可时直接放弃`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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
        tracker.imageGate?.complete(Unit)
        val results = stale.awaitAll()
        blockers.awaitAll()

        // 旧请求取得许可后复查范围，直接放弃，没有进入抽取。
        assertEquals(2, tracker.started.size)
        assertTrue(results.all { preview -> preview.image == null && preview.durationMillis == null })
        assertEquals(2, imageFileCount())

        // 放弃不计失败退避：重新纳入范围后立即抽取。
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(oldVisible.location))
        assertNotNull(bounded.prefetch(oldVisible).image)
        assertEquals(3, tracker.started.size)
        assertEquals(3, imageFileCount())
    }

    @Test
    fun `放弃结果不共享，同键可见等待者重新抽取`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blockers = listOf("占位1", "占位2").map { name ->
            async { bounded.preview(network("$name.mkv")) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

        val file = network("同键.mkv")
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))
        // 离屏预取作 leader，同键可见请求挂在同一次抽取上等待。
        val leader = async { bounded.prefetch(file) }
        runCurrent()
        val waiter = async { bounded.preview(file) }
        runCurrent()
        assertEquals(2, tracker.started.size)

        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        tracker.imageGate?.complete(Unit)
        // 可见请求取消同键预取；预取以取消收尾，可见请求自己抽取一次。
        runCatching { leader.await() }
        val waiterPreview = waiter.await()
        blockers.awaitAll()

        assertNotNull(waiterPreview.image)
        assertEquals(1, tracker.started.count { it == file.location })
        assertEquals(3, imageFileCount())
    }

    @Test
    fun `时长补读放弃不共享，同键可见等待者重新补读`() = runTest {
        val tracker = GatedExtractor(durationFailure = IllegalStateException("无时长"))
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val file = local("同键时长.mkv")
        tracker.imageGate = null
        assertNotNull(bounded.preview(file).image)
        assertEquals(1, tracker.durationRequests.count { it == file.location })

        tracker.durationFailure = null
        tracker.durationMillis = 500L
        tracker.imageGate = CompletableDeferred()
        val blockers = listOf("占位1", "占位2").map { name ->
            async { bounded.preview(local("$name.mkv")) }
        }
        runCurrent()
        assertEquals(3, tracker.started.size)

        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = setOf(file.location))
        val leader = async { bounded.prefetch(file) }
        runCurrent()
        val waiter = async { bounded.preview(file) }
        runCurrent()
        assertEquals(1, tracker.durationRequests.count { it == file.location })

        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        tracker.imageGate?.complete(Unit)
        // 可见请求取消同键预取；预取以取消收尾。
        runCatching { leader.await() }
        val waiterPreview = waiter.await()
        blockers.awaitAll()

        assertNotNull(waiterPreview.image)
        assertEquals(500L, waiterPreview.durationMillis)
        // 预热 1 次 + 等待者 1 次；leader 被取消时没有补读。
        assertEquals(2, tracker.durationRequests.count { it == file.location })
        assertEquals(3, imageFileCount())
    }

    @Test
    fun `放弃不清除已过期的抽取退避记录`() = runTest {
        var now = 1_000L
        val tracker = RetryAfterFailureExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory = directory, maxBytes = 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            monotonicClockMillis = { now },
        )
        val file = network("退避.mkv")

        assertNull(bounded.preview(file).image)
        assertEquals(1, tracker.requests)

        // 退避已过期：预取在范围内复查时被放弃，但既有记录不能被清掉。
        now += 30_000
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        assertNull(bounded.prefetch(file).image)
        assertEquals(1, tracker.requests)

        // 下一次真实失败按 attempts=2 退避 60 秒，而不是重新从 30 秒开始。
        now += 1
        assertNull(bounded.preview(file).image)
        assertEquals(2, tracker.requests)
        now += 59_999
        assertNull(bounded.preview(file).image)
        assertEquals(2, tracker.requests)
        now += 1
        assertNull(bounded.preview(file).image)
        assertEquals(3, tracker.requests)
    }

    @Test
    fun `可见项先于预取项取得许可`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
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

        tracker.imageGate?.complete(Unit)
        shown.await()
        prefetched.forEach { job -> runCatching { job.await() } }
        blockers.awaitAll()

        val visibleIndex = tracker.started.indexOf(visible.location)
        assertTrue(visibleIndex >= 2)
        // 可见请求到达后取消同源排队预取，预取不再进入抽取。
        assertTrue(prefetches.none { file -> tracker.started.contains(file.location) })
    }

    @Test
    fun `可见请求不因范围变化被放弃`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blockers = listOf("占位1", "占位2").map { name ->
            async { bounded.preview(network("$name.mkv")) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

        val visible = network("可见.mkv")
        bounded.updatePreviewWindow(visibleLocations = setOf(visible.location), prefetchLocations = emptySet())
        val shown = async { bounded.preview(visible) }
        runCurrent()
        // 可见卡片不会被重试：范围变化后仍须照常抽取。
        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        tracker.imageGate?.complete(Unit)

        assertNotNull(shown.await().image)
        blockers.awaitAll()
        assertTrue(tracker.started.contains(visible.location))
    }

    @Test
    fun `本地预取离开范围后取得许可不抽取`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blockers = listOf("占位1", "占位2").map { name ->
            async { bounded.preview(local("$name.mkv")) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

        val staleFile = local("旧.mkv")
        bounded.updatePreviewWindow(
            visibleLocations = emptySet(),
            prefetchLocations = setOf(staleFile.location),
        )
        val stale = async { bounded.prefetch(staleFile) }
        runCurrent()
        assertEquals(2, tracker.started.size)

        bounded.updatePreviewWindow(visibleLocations = emptySet(), prefetchLocations = emptySet())
        tracker.imageGate?.complete(Unit)
        val result = stale.await()
        blockers.awaitAll()

        assertEquals(2, tracker.started.size)
        assertNull(result.image)
        assertEquals(2, imageFileCount())
    }

    @Test
    fun `本地预取离开范围后取得许可不补读时长`() = runTest {
        val tracker = GatedExtractor(durationFailure = IllegalStateException("无时长"))
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val cached = local("旧.mkv")
        // 先落一张图为缓存，时长读取失败，记录为“时长未知”。
        tracker.imageGate = null
        assertNotNull(bounded.preview(cached).image)
        assertEquals(1, tracker.durationRequests.count { it == cached.location })

        tracker.imageGate = CompletableDeferred()
        val blockers = listOf("占位1", "占位2").map { name ->
            async { bounded.preview(local("$name.mkv")) }
        }
        runCurrent()
        // 1 次预热抽取 + 2 个占满本地许可的请求。
        assertEquals(3, tracker.started.size)

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
        blockers.awaitAll()

        // 放弃后没有补读时长，也没有记退避。
        assertEquals(1, tracker.durationRequests.count { it == cached.location })

        bounded.updatePreviewWindow(visibleLocations = setOf(cached.location), prefetchLocations = emptySet())
        assertNotNull(bounded.preview(cached).image)
        assertEquals(2, tracker.durationRequests.count { it == cached.location })
    }

    @Test
    fun `取消排队中的预取后可见请求先于新预取取得许可`() = runTest {
        val tracker = GatedExtractor()
        val bounded = CachedMediaPreviewSource(
            sourceKeyDispatcher = Dispatchers.Unconfined,
            extractor = tracker,
            cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
        )
        val blockers = listOf("占位1", "占位2").map { name ->
            async { bounded.preview(network("$name.mkv")) }
        }
        runCurrent()
        assertEquals(2, tracker.started.size)

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
        assertEquals(2, tracker.started.size)

        tracker.imageGate?.complete(Unit)
        assertNotNull(visible.await().image)
        runCatching { later.await() }
        blockers.awaitAll()

        val visibleIndex = tracker.started.indexOf("smb://nas/media/可见.mkv")
        assertTrue(visibleIndex > 1)
        assertTrue(tracker.maxConcurrent <= 2)
        assertFalse(tracker.started.contains(queuedFile.location))
        // 可见请求到达后取消排队中的预取，新预取不再进入抽取。
        assertFalse(tracker.started.contains(laterFile.location))
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

    private fun fileAt(location: String) = MediaFile(
        name = location.substringAfterLast('/'),
        location = location,
        lastModified = 1_700_000_000_000,
        source = MediaSource.Network,
    )

    private fun cacheKey(file: MediaFile, policy: ThumbnailPolicy = ThumbnailPolicy()) =
        thumbnailCacheKey(file, policy, previewTargetWidth(file, policy))
}

/** 抽取开始后等待闸门，用来稳定构造并发名额被占满、后续请求排队的场景。 */
private class GatedExtractor(
    var imageGate: CompletableDeferred<Unit>? = CompletableDeferred(),
    var durationFailure: Exception? = null,
    var durationMillis: Long? = null,
) : MediaPreviewExtractor {

    val started = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val durationRequests = mutableListOf<String>()
    var concurrent = 0
    var maxConcurrent = 0

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        started += file.location
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
        durationFailure?.let { throw it }
        return durationMillis
    }
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

/** 抽取一直挂起，取消后才结束，用来验证许可归还。 */
private class CancellableExtractor : MediaPreviewExtractor {

    val started = mutableListOf<String>()

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        started += file.location
        awaitCancellation()
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}
