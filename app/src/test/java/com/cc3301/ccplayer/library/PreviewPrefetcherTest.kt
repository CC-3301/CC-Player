package com.cc3301.ccplayer.library

import kotlinx.coroutines.CancellationException
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
        // 预取按列表从上到下发起：上方行 1、2，随后下方行 6、7。
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
    fun `快速滑过时取消真正离屏的旧预取`() = runTest {
        val source = RecordingPreviewSource()
        val prefetcher = PreviewPrefetcher(source, backgroundScope, prefetchDelayMillis = 100)

        prefetcher.update(entries, firstVisible = 2, lastVisible = 3, rowSize = 2)
        advanceTimeBy(50)
        prefetcher.update(entries, firstVisible = 5, lastVisible = 6, rowSize = 2)
        advanceTimeBy(100)
        runCurrent()

        // 5 已进入可见区，保留在途请求；旧窗口的 4、5 先到点，随后新窗口的 3、7、8 发起。
        assertEquals(listOf(4, 5, 3, 7, 8), source.previewed.map { entry -> entries.indexOf(entry) })
    }
}

/** 记录预取与取消的假预览源。 */
private class RecordingPreviewSource : MediaPreviewSource {

    val previewed = mutableListOf<MediaFile>()
    val cancelled = mutableListOf<MediaFile>()

    override suspend fun preview(file: MediaFile): MediaPreview {
        previewed += file
        try {
            delay(10_000)
        } catch (cancellation: CancellationException) {
            cancelled += file
            throw cancellation
        }
        return MediaPreview(image = null, durationMillis = null)
    }
}
