package com.cc3301.ccplayer.library

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewPrefetcherTest {

    private val entries = (0 until 10).map { index ->
        MediaFile(name = "第$index 集.mkv", location = "smb://nas/media/第$index 集.mkv")
    }

    @Test
    fun `只预取可见窗口前后各一行`() {
        val wanted = prefetchEntries(entries, firstVisible = 3, lastVisible = 5, rowSize = 2)

        assertEquals(listOf(1, 2, 6, 7), wanted.map { entry -> entries.indexOf(entry) })
    }

    @Test
    fun `预取跳过子目录`() {
        val withDirectory = entries.toMutableList().also { list -> list[1] = list[1].copy(isDirectory = true) }

        val wanted = prefetchEntries(withDirectory, firstVisible = 3, lastVisible = 5, rowSize = 2)

        assertEquals(listOf(2, 6, 7), wanted.map { entry -> withDirectory.indexOf(entry) })
    }

    @Test
    fun `预取延迟发起，让可见项先请求`() = runTest {
        val source = RecordingPreviewSource()
        val prefetcher = PreviewPrefetcher(source, backgroundScope, prefetchDelayMillis = 100)

        prefetcher.update(entries, firstVisible = 3, lastVisible = 5, rowSize = 2)
        runCurrent()
        assertTrue(source.previewed.isEmpty())

        advanceTimeBy(100)
        runCurrent()
        // 同一窗口的预取在延迟到点后按索引顺序发起；验证窗口时序，不是可见列表顺序。
        assertEquals(listOf(1, 2, 6, 7), source.previewed.map { entry -> entries.indexOf(entry) })
    }

    @Test
    fun `窗口变化取消离开范围的预取`() = runTest {
        val source = RecordingPreviewSource()
        val prefetcher = PreviewPrefetcher(source, backgroundScope, prefetchDelayMillis = 100)

        prefetcher.update(entries, firstVisible = 3, lastVisible = 5, rowSize = 2)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(4, source.previewed.size)

        prefetcher.update(entries, firstVisible = 6, lastVisible = 8, rowSize = 2)
        runCurrent()

        // 6、7 已进入可见区，保留在途请求；只取消真正离开范围的 1、2。
        assertEquals(listOf(1, 2), source.cancelled.map { entry -> entries.indexOf(entry) }.sorted())
        assertEquals(4, source.previewed.size)
    }

    @Test
    fun `进入可见区的预取不取消，交给可见项复用`() = runTest {
        val source = RecordingPreviewSource()
        val prefetcher = PreviewPrefetcher(source, backgroundScope, prefetchDelayMillis = 100)

        prefetcher.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(3), source.previewed.map { entry -> entries.indexOf(entry) })

        prefetcher.update(entries, firstVisible = 2, lastVisible = 4, rowSize = 1)
        runCurrent()

        assertTrue(source.cancelled.isEmpty())
        assertEquals(listOf(3), source.previewed.map { entry -> entries.indexOf(entry) })
    }

    @Test
    fun `预取前先下发可见与预取范围`() = runTest {
        val source = RecordingPreviewSource()
        val prefetcher = PreviewPrefetcher(source, backgroundScope, prefetchDelayMillis = 100)

        // 首帧全部可见，预取集合为空，但范围仍要先下发。
        prefetcher.update(entries, firstVisible = 0, lastVisible = 9, rowSize = 2)
        runCurrent()
        val firstWindow = source.events.first() as PreviewEvent.Window
        assertEquals(entries.map { entry -> entry.location }.toSet(), firstWindow.visible)
        assertTrue(firstWindow.prefetch.isEmpty())
        assertTrue(source.events.none { event -> event is PreviewEvent.Preview })

        prefetcher.update(entries, firstVisible = 3, lastVisible = 5, rowSize = 2)
        runCurrent()
        val rangeWindow = source.events.filterIsInstance<PreviewEvent.Window>().last()
        assertEquals(listOf(3, 4, 5), windowIndices(rangeWindow.visible, entries))
        assertEquals(listOf(1, 2, 6, 7), windowIndices(rangeWindow.prefetch, entries))
        assertTrue(source.events.none { event -> event is PreviewEvent.Preview })

        advanceTimeBy(100)
        runCurrent()
        val lastWindowIndex = source.events.indexOfLast { event -> event is PreviewEvent.Window }
        val firstPreviewIndex = source.events.indexOfFirst { event -> event is PreviewEvent.Preview }
        assertTrue(lastWindowIndex in 0 until firstPreviewIndex)
        assertEquals(listOf(1, 2, 6, 7), source.previewed.map { entry -> entries.indexOf(entry) })
    }

    @Test
    fun `取消全部预取后不再抽取也不落盘`() = runTest {
        val directory = Files.createTempDirectory("prefetch-cancel").toFile()
        try {
            val extractor = FakeMediaPreviewExtractor(imageBytes = byteArrayOf(1))
            val source = CachedMediaPreviewSource(
            extractor = extractor,
                cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
            )
            val prefetcher = PreviewPrefetcher(source, backgroundScope, prefetchDelayMillis = 100)

            prefetcher.update(entries, firstVisible = 0, lastVisible = 1, rowSize = 1)
            prefetcher.cancelAll()
            advanceTimeBy(100)
            runCurrent()

            assertTrue(extractor.imageRequests.isEmpty())
            assertTrue(directory.listFiles().isNullOrEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `快速滑过时取消真正离屏的旧预取`() = runTest {
        val source = RecordingPreviewSource()
        val prefetcher = PreviewPrefetcher(source, backgroundScope, prefetchDelayMillis = 100)

        prefetcher.update(entries, firstVisible = 2, lastVisible = 3, rowSize = 2)
        advanceTimeBy(50)
        prefetcher.update(entries, firstVisible = 5, lastVisible = 6, rowSize = 2)
        advanceTimeBy(100)
        runCurrent()

        // 验证在途优先与窗口时序：旧窗口的 4、5 保留在先，新窗口的 3、7、8 随后发起。
        assertEquals(listOf(4, 5, 3, 7, 8), source.previewed.map { entry -> entries.indexOf(entry) })
    }
}

/** 预览源收到的统一事件序列，用于断言下发范围与发起预取的先后。 */
private sealed interface PreviewEvent {
    data class Window(val visible: Set<String>, val prefetch: Set<String>) : PreviewEvent
    data class Preview(val file: MediaFile) : PreviewEvent
}

/** 记录预取与取消的假预览源。 */
private class RecordingPreviewSource : MediaPreviewSource {

    val previewed = mutableListOf<MediaFile>()
    val cancelled = mutableListOf<MediaFile>()
    val events = mutableListOf<PreviewEvent>()

    override suspend fun preview(file: MediaFile): MediaPreview {
        previewed += file
        events += PreviewEvent.Preview(file)
        try {
            delay(10_000)
        } catch (cancellation: CancellationException) {
            cancelled += file
            throw cancellation
        }
        return MediaPreview(image = null, durationMillis = null)
    }

    override fun updatePreviewWindow(visibleLocations: Set<String>, prefetchLocations: Set<String>) {
        events += PreviewEvent.Window(visibleLocations, prefetchLocations)
    }
}

/** 把位置集合换成条目索引，便于断言。 */
private fun windowIndices(locations: Set<String>, entries: List<MediaFile>): List<Int> =
    locations.map { location -> entries.indexOfFirst { it.location == location } }.sorted()
