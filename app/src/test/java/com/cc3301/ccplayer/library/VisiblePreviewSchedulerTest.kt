package com.cc3301.ccplayer.library

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `滚动离屏再回后重新下发并收到结果`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)
        val request = async { scheduler.preview(entries[0], 0) }
        runCurrent()
        assertEquals(listOf(0), source.started)

        scheduler.update(entries, firstVisible = 5, lastVisible = 6, rowSize = 1)
        runCurrent()
        assertEquals(listOf(0), source.cancelled)
        assertEquals(listOf(0), source.started)

        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)
        runCurrent()
        assertEquals(listOf(0, 0), source.started)

        source.gate?.complete(Unit)
        runCurrent()
        assertEquals(1L, withTimeout(1_000) { request.await() }.durationMillis)
    }

    @Test
    fun `切换视图重置后仍能重新下发`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 1, rowSize = 1)
        val request = async { scheduler.preview(entries[0], 0) }
        runCurrent()
        assertEquals(listOf(0), source.started)

        scheduler.reset()
        scheduler.update(entries, firstVisible = 0, lastVisible = 3, rowSize = 1)
        runCurrent()

        assertEquals(listOf(0), source.cancelled)
        assertEquals(listOf(0, 0), source.started)
        source.gate?.complete(Unit)
        runCurrent()
        assertEquals(1L, withTimeout(1_000) { request.await() }.durationMillis)
    }

    @Test
    fun `排序变化后按新索引重新下发`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 1, rowSize = 1)
        val request = async { scheduler.preview(entries[1], 1) }
        runCurrent()
        assertEquals(listOf(1), source.started)

        val reversed = entries.reversed()
        val newIndex = reversed.indexOf(entries[1])
        scheduler.reset()
        scheduler.update(reversed, firstVisible = 0, lastVisible = reversed.lastIndex, rowSize = 1)
        val resubscribed = async { scheduler.preview(entries[1], newIndex) }
        runCurrent()

        assertEquals(newIndex, source.started.last())
        source.gate?.complete(Unit)
        runCurrent()
        assertEquals((newIndex + 1).toLong(), withTimeout(1_000) { request.await() }.durationMillis)
        assertEquals((newIndex + 1).toLong(), withTimeout(1_000) { resubscribed.await() }.durationMillis)
    }

    @Test
    fun `目录重载取消旧订阅后新请求可下发`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)
        val old = async { scheduler.preview(entries[0], 0) }
        runCurrent()

        // 目录重载：旧条目被销毁，订阅取消。
        old.cancel()
        runCurrent()

        val freshEntry = MediaFile(
            name = "新目录.mkv",
            location = "smb://nas/media/新目录.mkv",
            source = MediaSource.Network,
        )
        scheduler.reset()
        scheduler.update(listOf(freshEntry), firstVisible = 0, lastVisible = 0, rowSize = 1)
        val fresh = async { scheduler.preview(freshEntry, 0) }
        runCurrent()

        source.gate?.complete(Unit)
        runCurrent()
        assertEquals(1L, withTimeout(1_000) { fresh.await() }.durationMillis)
    }

    @Test
    fun `新索引进入可见区后能收到结果`() = runTest {
        val source = SchedulerRecordingSource()
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)

        // 新条目先于窗口更新调用 preview：不能按旧窗口直接判空。
        val entering = async { scheduler.preview(entries[5], 5) }
        runCurrent()
        assertTrue(source.started.isEmpty())

        scheduler.update(entries, firstVisible = 0, lastVisible = 6, rowSize = 1)
        runCurrent()

        assertNotNull(withTimeout(1_000) { entering.await() })
        assertEquals(listOf(5), source.started)
    }

    @Test
    fun `空范围暂停在途请求并清空预取窗口`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)
        val requests = (0..2).map { index -> async { scheduler.preview(entries[index], index) } }
        runCurrent()

        scheduler.update(entries, firstVisible = -1, lastVisible = -1, rowSize = 1)
        runCurrent()

        assertEquals(listOf(0, 1, 2), source.cancelled.sorted())
        assertEquals(listOf(0, 1, 2), source.started)
        assertTrue(source.windows.getValue("visible").isEmpty())
        assertTrue(source.windows.getValue("prefetch").isEmpty())

        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)
        runCurrent()
        source.gate?.complete(Unit)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), requests.map { request -> request.await().durationMillis })
    }

    @Test
    fun `同一位置取消一个订阅不影响另一个`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)
        val first = async { scheduler.preview(entries[0], 0) }
        val second = async { scheduler.preview(entries[0], 0) }
        runCurrent()

        first.cancel()
        runCurrent()

        source.gate?.complete(Unit)
        runCurrent()
        assertEquals(1L, withTimeout(1_000) { second.await() }.durationMillis)
    }

    @Test
    fun `取消全部后不再下发新请求`() = runTest {
        val source = SchedulerRecordingSource()
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)

        scheduler.cancelAll()
        val request = async { scheduler.preview(entries[0], 0) }
        runCurrent()

        assertNull(request.await().durationMillis)
        assertTrue(source.started.isEmpty())
    }
}

/** 记录下发与取消的假预览源；[gate] 非空时压住抽取，用来构造滚动与重置。 */
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
        // 用非空时长区分“已出结果”和占位图。
        return MediaPreview(image = null, durationMillis = index + 1L)
    }

    override suspend fun prefetch(file: MediaFile): MediaPreview = preview(file)

    override fun updatePreviewWindow(visibleLocations: Set<String>, prefetchLocations: Set<String>) {
        windows["visible"] = visibleLocations
        windows["prefetch"] = prefetchLocations
    }
}
