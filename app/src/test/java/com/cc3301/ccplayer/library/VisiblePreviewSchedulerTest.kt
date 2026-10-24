package com.cc3301.ccplayer.library

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisiblePreviewSchedulerTest {

    private val entries = (0 until 8).map { index ->
        MediaFile(
            name = "第$index 集.mkv",
            location = "smb://nas/media/第$index 集.mkv",
            source = MediaSource.Network,
        )
    }

    private fun scheduler(source: MediaPreviewSource, scope: CoroutineScope) = VisiblePreviewScheduler(
        source = source,
        scope = scope,
        prefetcher = PreviewPrefetcher(source, scope, prefetchDelayMillis = 100),
    )

    @Test
    fun `可见请求按列表索引从上到下下发`() = runTest {
        val source = SchedulerRecordingSource()
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = entries.lastIndex, rowSize = 1)

        val requests = listOf(5, 2, 7, 0, 3, 1, 6, 4).map { index ->
            async { scheduler.preview(entries[index], index) }
        }
        runCurrent()
        requests.forEach { it.await() }

        assertEquals((0..7).toList(), source.started)
    }

    @Test
    fun `快速滚动后离屏请求取消且不再抽取`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)

        val requests = (0..2).map { index -> async { scheduler.preview(entries[index], index) } }
        runCurrent()
        assertEquals(listOf(0, 1, 2), source.started)

        scheduler.update(entries, firstVisible = 5, lastVisible = 6, rowSize = 1)
        runCurrent()

        assertEquals(listOf(0, 1, 2), source.cancelled.sorted())
        assertTrue(requests.map { it.await() }.all { preview -> preview.image == null })
        assertEquals(listOf(0, 1, 2), source.started)
    }

    @Test
    fun `不在可见范围的请求不下发也不消耗抽取`() = runTest {
        val source = SchedulerRecordingSource()
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 5, lastVisible = 6, rowSize = 1)

        val offscreen = async { scheduler.preview(entries[1], 1) }
        runCurrent()

        assertNull(offscreen.await().image)
        assertTrue(source.started.isEmpty())
    }

    @Test
    fun `取消全部后不再下发新请求`() = runTest {
        val source = SchedulerRecordingSource()
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)

        scheduler.cancelAll()
        val request = async { scheduler.preview(entries[0], 0) }
        runCurrent()

        assertNull(request.await().image)
        assertTrue(source.started.isEmpty())
    }
}

/** 记录下发与取消的假预览源；[gate] 非空时压住抽取，用来构造快速滚动。 */
private class SchedulerRecordingSource : MediaPreviewSource {

    val started = mutableListOf<Int>()
    val cancelled = mutableListOf<Int>()
    val windows = ConcurrentHashMap<String, Set<String>>()

    var gate: CompletableDeferred<Unit>? = null

    override suspend fun preview(file: MediaFile): MediaPreview = visiblePreview(file, 0)

    override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
        started += index
        try {
            gate?.await()
        } catch (cancellation: CancellationException) {
            cancelled += index
            throw cancellation
        }
        return MediaPreview(image = null, durationMillis = null)
    }

    override suspend fun prefetch(file: MediaFile): MediaPreview = preview(file)

    override fun updatePreviewWindow(visibleLocations: Set<String>, prefetchLocations: Set<String>) {
        windows["visible"] = visibleLocations
        windows["prefetch"] = prefetchLocations
    }
}
