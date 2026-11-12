package com.cc3301.ccplayer.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun `第一屏不等可见窗口就下发`() = runTest {
        val source = SchedulerRecordingSource()
        val scheduler = scheduler(source, backgroundScope)

        // 还没有 update：列表布局没出来，卡片刚订阅。
        val requests = (0..2).map { index -> async { scheduler.preview(entries[index], index) } }
        runCurrent()

        assertEquals(listOf(0, 1, 2), source.started)
        requests.forEach { it.await() }
    }

    @Test
    fun `拖动后排队中的旧任务丢弃，已进入解码的那一张跑完`() = runTest {
        val source = SchedulerRecordingSource().apply {
            gate = CompletableDeferred()
            extractingLocations = setOf(entries[0].location)
        }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)
        val decoding = async { scheduler.preview(entries[0], 0) }
        // 排队中的这一张被暂停后订阅保留，调用方会一直等下一次可见，这里不等它。
        val queued = launch {
            try {
                scheduler.preview(entries[1], 1)
            } catch (cancellation: CancellationException) {
                // 暂停不动订阅，只有订阅取消才会取消等待。
            }
        }
        runCurrent()
        assertEquals(listOf(0, 1), source.started)

        // 拖到后面：旧范围整体离屏。
        scheduler.update(entries, firstVisible = 5, lastVisible = 6, rowSize = 1)
        runCurrent()

        assertEquals(listOf(1), source.cancelled)

        source.gate?.complete(Unit)
        runCurrent()

        assertEquals(1L, withTimeout(1_000) { decoding.await() }.durationMillis)
        queued.cancel()
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
    fun `离屏取消带上原因，回屏后重新下发`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)
        val request = async { scheduler.preview(entries[0], 0) }
        runCurrent()
        assertEquals(listOf(0), source.started)

        scheduler.update(entries, firstVisible = 5, lastVisible = 5, rowSize = 1)
        runCurrent()
        assertEquals("离屏取消要带原因", listOf("离屏"), source.cancelReasons)

        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)
        runCurrent()
        assertEquals("回屏后重新入队", listOf(0, 0), source.started)
        request.cancel()
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
    fun `新条目先于窗口更新订阅也会立刻下发`() = runTest {
        val source = SchedulerRecordingSource()
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)

        // 新条目先于窗口更新订阅：不等窗口，立刻下发。
        val entering = async { scheduler.preview(entries[5], 5) }
        runCurrent()
        assertEquals(listOf(5), source.started)

        scheduler.update(entries, firstVisible = 0, lastVisible = 6, rowSize = 1)
        runCurrent()

        assertEquals(6L, withTimeout(1_000) { entering.await() }.durationMillis)
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

        // 整屏一次性下发入队，空范围把在途的三条都暂停。
        assertEquals(listOf(0, 1, 2), source.cancelled.sorted())
        assertEquals(listOf(0, 1, 2), source.started)
        assertTrue(source.windows.getValue("visible").isEmpty())
        assertTrue(source.windows.getValue("prefetch").isEmpty())

        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)
        runCurrent()
        assertEquals(listOf(0, 1, 2, 0, 1, 2), source.started)
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
    fun `源抛 Error 时按占位图收尾并可重订`() = runTest {
        val source = SchedulerRecordingSource().apply { failure = OutOfMemoryError("模拟解码崩溃") }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)

        val first = async { scheduler.preview(entries[0], 0) }
        runCurrent()

        assertNull(withTimeout(1_000) { first.await() }.durationMillis)

        source.failure = null
        val second = async { scheduler.preview(entries[0], 0) }
        runCurrent()

        assertEquals(1L, withTimeout(1_000) { second.await() }.durationMillis)
        assertEquals(2, source.started.size)
    }

    @Test
    fun `最后订阅者取消后请求移除且可重订`() = runTest {
        val source = SchedulerRecordingSource().apply { gate = CompletableDeferred() }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)

        val first = async { scheduler.preview(entries[0], 0) }
        runCurrent()
        first.cancel()
        runCurrent()

        val second = async { scheduler.preview(entries[0], 0) }
        runCurrent()
        source.gate?.complete(Unit)
        runCurrent()

        assertEquals(listOf(0, 0), source.started)
        assertEquals(1L, withTimeout(1_000) { second.await() }.durationMillis)
    }

    @Test
    fun `离屏取消后快速回屏，旧任务迟到收尾不覆盖新结果`() = runTest {
        val source = LateCancelSource()
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)
        val request = async { scheduler.preview(entries[0], 0) }
        runCurrent()
        assertEquals(listOf(0), source.started)

        // 离屏：旧 attempt 已作废，但源要等 cancelGate 才真正结束。
        scheduler.update(entries, firstVisible = 1, lastVisible = 1, rowSize = 1)
        runCurrent()
        assertEquals(listOf(0), source.cancelled)

        // 快速回屏：新 attempt 立即下发，与旧 attempt 的迟到收尾并存。
        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)
        runCurrent()
        assertEquals(listOf(0, 0), source.started)

        // 旧 attempt 的收尾不得回填占位图或顶替新结果。
        source.cancelGate.complete(Unit)
        runCurrent()
        source.secondGate.complete(Unit)
        runCurrent()

        assertEquals(222L, withTimeout(1_000) { request.await() }.durationMillis)
    }

    @Test
    fun `任务完成回调进行中取消全部不抛并发修改`() = runBlocking {
        val failures = CopyOnWriteArrayList<Throwable>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scheduler-completion").apply {
                setUncaughtExceptionHandler { _, failure -> failures += failure }
            }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            repeat(200) {
                // 整屏一次性下发：八条都在抽取前挂住，再与取消全部并发收尾。
                val entered = CountDownLatch(entries.size)
                val gate = CompletableDeferred<Unit>()
                val source = CompletionGateSource(entered, gate)
                val scheduler = VisiblePreviewScheduler(
                    source = source,
                    scope = scope,
                    prefetcher = PreviewPrefetcher(source, scope, prefetchDelayMillis = 60_000),
                )
                scheduler.update(entries, firstVisible = 0, lastVisible = entries.lastIndex, rowSize = 1)
                val requests = entries.mapIndexed { index, file ->
                    scope.launch { scheduler.preview(file, index) }
                }
                assertTrue("请求应先全部进入抽取", entered.await(5, TimeUnit.SECONDS))

                // 任务进入完成回调的同时从测试线程取消全部，快照遍历不得被回调修改。
                gate.complete(Unit)
                scheduler.cancelAll()
                requests.forEach { request -> request.join() }
            }
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
        assertTrue("完成回调并发清理抛异常：$failures", failures.isEmpty())
    }

    @Test
    fun `一屏可见项按列表索引依次出图`() = runTest {
        val directory: File = Files.createTempDirectory("scheduler-order").toFile()
        try {
            val extractor = OrderRecordingExtractor()
            val source = CachedMediaPreviewSource(
                extractor = extractor,
                cache = ThumbnailCache(directory, 1 shl 20, ioDispatcher = Dispatchers.Unconfined),
                backgroundScope = backgroundScope,
            )
            val scheduler = VisiblePreviewScheduler(
                source = source,
                scope = backgroundScope,
                prefetcher = PreviewPrefetcher(source, backgroundScope, prefetchDelayMillis = 60_000),
            )
            scheduler.update(entries, firstVisible = 0, lastVisible = entries.lastIndex, rowSize = 1)

            // 乱序订阅，抽图顺序仍按列表索引。
            val subscribers = listOf(5, 2, 7, 0, 3, 1, 6, 4).map { index ->
                async { scheduler.preview(entries[index], index) }
            }
            subscribers.forEach { subscriber -> subscriber.await() }

            assertEquals("出图顺序与索引一致", (0..entries.lastIndex).toList(), extractor.started)
            scheduler.cancelAll()
        } finally {
            directory.deleteRecursively()
        }
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

    @Test
    fun `失败项的重试排在可见项全部出图之后`() = runTest {
        val order = mutableListOf<Int>()
        val states = RetryStates()
        val source = object : MediaPreviewSource {
            override suspend fun preview(file: MediaFile): MediaPreview = MediaPreview(null, null)

            override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
                order += index
                val fails = index == 0 && states.stateOf(file).attempts < 2
                if (fails) states.fail(file)
                return MediaPreview(image = null, durationMillis = null, retryable = fails)
            }

            override fun previewEntryState(file: MediaFile): PreviewEntryState = states.stateOf(file)
        }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)

        val requests = (0..2).map { index -> async { scheduler.preview(entries[index], index) } }
        advanceUntilIdle()
        requests.forEach { it.await() }

        assertEquals("先出 1、2，再重试 0", listOf(0, 1, 2, 0, 0), order)
    }

    @Test
    fun `失败项不越过仍在出图的可见项`() = runTest {
        val order = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val states = RetryStates()
        val source = object : MediaPreviewSource {
            override suspend fun preview(file: MediaFile): MediaPreview = MediaPreview(null, null)

            override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
                order += index
                when (index) {
                    0 -> {
                        states.fail(file)
                        return MediaPreview(image = null, durationMillis = null, retryable = true)
                    }
                    1 -> gate.await()
                }
                return MediaPreview(null, null)
            }

            override fun previewEntryState(file: MediaFile): PreviewEntryState = states.stateOf(file)
        }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 2, rowSize = 1)

        val requests = (0..2).map { index -> async { scheduler.preview(entries[index], index) } }
        // 重试计时早到，但 1 还没出图：失败项不得插到前面。
        advanceTimeBy(5_000)
        assertEquals(listOf(0, 1, 2), order)

        gate.complete(Unit)
        advanceUntilIdle()
        requests.forEach { it.await() }

        assertEquals("1 出图后才轮到 0 的重试", 0, order.last())
        assertEquals("首次一次加 5 次重试", 6, order.count { it == 0 })
    }

    @Test
    fun `失败先跳过，可见项出完后按 1 到 5 秒递进重试且最多 5 次`() = runTest {
        val times = mutableListOf<Long>()
        val states = RetryStates()
        val source = object : MediaPreviewSource {
            override suspend fun preview(file: MediaFile): MediaPreview = MediaPreview(null, null)

            override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
                states.fail(file)
                times += testScheduler.currentTime
                return MediaPreview(image = null, durationMillis = null, retryable = true)
            }

            override fun previewEntryState(file: MediaFile): PreviewEntryState = states.stateOf(file)
        }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(listOf(entries[0]), firstVisible = 0, lastVisible = 0, rowSize = 1)

        val request = async { scheduler.preview(entries[0], 0) }
        advanceUntilIdle()
        assertNull(request.await().image)

        assertEquals("首次一次加 5 次重试", 6, times.size)
        assertEquals(
            "递进间隔为 1s、2s、3s、4s、5s",
            listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L),
            times.zipWithNext { previous, next -> next - previous },
        )
    }

    @Test
    fun `失败项离屏后重按当前间隔计时，回屏不 0 秒重试`() = runTest {
        val times = mutableListOf<Long>()
        val states = RetryStates()
        val source = object : MediaPreviewSource {
            override suspend fun preview(file: MediaFile): MediaPreview = MediaPreview(null, null)

            override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
                times += testScheduler.currentTime
                // 0 首次失败、重试成功；1 一次就出图。
                val fails = index == 0 && states.stateOf(file).attempts == 0
                if (fails) states.fail(file)
                return MediaPreview(
                    image = null,
                    durationMillis = if (fails) null else index + 1L,
                    retryable = fails,
                )
            }

            override fun previewEntryState(file: MediaFile): PreviewEntryState = states.stateOf(file)
        }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 1, rowSize = 1)
        val requests = (0..1).map { index -> async { scheduler.preview(entries[index], index) } }
        runCurrent()
        assertEquals("先出 1，0 先跳过", listOf(0L, 0L), times)

        // 重试间隔没到就离屏：离屏取消计时，回屏不再等这一整段。
        advanceTimeBy(500)
        scheduler.update(entries, firstVisible = 1, lastVisible = 1, rowSize = 1)
        runCurrent()
        scheduler.update(entries, firstVisible = 0, lastVisible = 1, rowSize = 1)
        runCurrent()

        assertNull(requests[0].await().image)
        assertEquals("回屏按当前间隔重试，不 0 秒重试", listOf(0L, 0L, 1_500L), times)
    }

    @Test
    fun `最后一个订阅者取消后失败项的重试计时仍会启动`() = runTest {
        val times = mutableListOf<Long>()
        val gate = CompletableDeferred<Unit>()
        val states = RetryStates()
        val source = object : MediaPreviewSource {
            override suspend fun preview(file: MediaFile): MediaPreview = MediaPreview(null, null)

            override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
                if (index == 0) {
                    times += testScheduler.currentTime
                    val first = states.stateOf(file).attempts == 0
                    if (first) states.fail(file)
                    return MediaPreview(null, null, retryable = first)
                }
                gate.await()
                return MediaPreview(null, 1L)
            }

            override fun previewEntryState(file: MediaFile): PreviewEntryState = states.stateOf(file)
        }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 1, rowSize = 1)
        val failed = async { scheduler.preview(entries[0], 0) }
        val fresh = async { scheduler.preview(entries[1], 1) }
        runCurrent()
        assertEquals("0 先失败跳过", listOf(0L), times)

        // 唯一的首轮项退了订阅：失败项的重试计时还没起，要靠这次收尾补上。
        fresh.cancel()
        runCurrent()
        assertEquals("取消首轮项不触发重试", listOf(0L), times)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("订阅收尾后重试计时照常启动", listOf(0L, 1_000L), times)
        assertNull(failed.await().image)
    }

    @Test
    fun `重试用尽后重新订阅再给一轮`() = runTest {
        var rounds = 0
        val target = entries[0]
        val states = RetryStates()
        val source = object : MediaPreviewSource {
            override suspend fun preview(file: MediaFile): MediaPreview = visiblePreview(file, 0)

            override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
                // 只数这一条：前后一行的预取窗口里还有相邻条目，不能把它们的请求算进来。
                if (file.location == target.location) rounds++
                states.fail(file)
                return MediaPreview(image = null, durationMillis = null, retryable = true)
            }

            override fun previewEntryState(file: MediaFile): PreviewEntryState = states.stateOf(file)

            // 新一轮订阅从零计：重试用尽后重新订阅仍会重抽。
            override fun beginVisiblePreview(file: MediaFile) {
                states.beginVisible(file)
            }

            // 预取不参与这条用例的计数，也不改变调度状态。
            override suspend fun prefetch(file: MediaFile): MediaPreview =
                MediaPreview(image = null, durationMillis = null)

            override fun updatePreviewWindow(
                visibleLocations: Set<String>,
                prefetchLocations: Set<String>,
            ) = Unit
        }
        val scheduler = scheduler(source, backgroundScope)
        scheduler.update(entries, firstVisible = 0, lastVisible = 0, rowSize = 1)

        val first = async { scheduler.preview(target, 0) }
        runCurrent()
        // 首发 1 次加重试 5 次；递进间隔合计 15 秒，留足推进时间。
        repeat(20) {
            if (!first.isCompleted) {
                advanceTimeBy(1_000)
                runCurrent()
            }
        }
        withTimeout(5_000) { first.await() }
        assertEquals("首发加 5 次重试", 6, rounds)

        // 重新订阅（离开可见范围再回来、回到前台都会发生）：调度器里已没有这一项，会重新给一轮。
        val second = async { scheduler.preview(target, 0) }
        runCurrent()
        assertEquals("重新订阅立即再抽一次", 7, rounds)
        // 这一轮还在重试中：断言完直接取消，不等它跑完。
        second.cancel()
    }

    @Test
    fun `本地失败次数从抽帧状态读，重新订阅后只剩一次机会`() = runTest {
        val directory = Files.createTempDirectory("preview-scheduler").toFile()
        try {
            val extractor = FakeMediaPreviewExtractor().apply { imageFailure = IOException("读取失败") }
            val source = CachedMediaPreviewSource(
                extractor = extractor,
                cache = ThumbnailCache(
                    directory = directory,
                    maxBytes = 1 shl 20,
                    ioDispatcher = Dispatchers.Unconfined,
                ),
                backgroundScope = backgroundScope,
            )
            val target = MediaFile(
                name = "坏文件.mkv",
                location = "root/坏文件.mkv",
                source = MediaSource.Local,
            )
            val scheduler = scheduler(source, backgroundScope)
            scheduler.update(listOf(target), firstVisible = 0, lastVisible = 0, rowSize = 1)

            val first = async { scheduler.preview(target, 0) }
            advanceUntilIdle()
            first.await()

            assertEquals("首发加三次重试，次数由抽帧状态给出", 4, extractor.imageRequests.size)

            val second = async { scheduler.preview(target, 0) }
            advanceUntilIdle()
            second.await()

            assertEquals("重新订阅后本地只剩一次机会", 5, extractor.imageRequests.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}

/** 假源里的抽帧状态：按位置各记一份，调度器读同一份决定重试节奏。 */
private class RetryStates {

    private val states = mutableMapOf<String, PreviewEntryState>()

    fun stateOf(file: MediaFile): PreviewEntryState = states.getOrPut(file.location) { PreviewEntryState() }

    /** 记一次失败。 */
    fun fail(file: MediaFile) {
        stateOf(file).fail(PreviewFailureStage.Read)
    }

    /** 新一轮可见订阅：已试次数从零计。 */
    fun beginVisible(file: MediaFile) {
        stateOf(file).resetAttempts()
    }
}

/** 首次抽取取消后延迟结束，用来构造旧 attempt 迟到收尾。 */
private class LateCancelSource : MediaPreviewSource {

    val started = mutableListOf<Int>()
    val cancelled = mutableListOf<Int>()
    val cancelGate = CompletableDeferred<Unit>()
    val secondGate = CompletableDeferred<Unit>()

    private val firstGate = CompletableDeferred<Unit>()
    private var calls = 0

    override suspend fun preview(file: MediaFile): MediaPreview = visiblePreview(file, 0)

    override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
        val call = calls++
        started += index
        if (call == 0) {
            try {
                firstGate.await()
            } catch (cancellation: CancellationException) {
                cancelled += index
                // 取消真正生效前先压住，让旧 attempt 的收尾晚于新 attempt。
                withContext(NonCancellable) { cancelGate.await() }
                throw cancellation
            }
            return MediaPreview(image = null, durationMillis = 111)
        }
        secondGate.await()
        return MediaPreview(image = null, durationMillis = 222)
    }

    override suspend fun prefetch(file: MediaFile): MediaPreview = preview(file)

    override fun updatePreviewWindow(visibleLocations: Set<String>, prefetchLocations: Set<String>) = Unit
}

/** 抽取等闸门放行；用来让任务集中进入完成回调。 */
private class CompletionGateSource(
    private val entered: CountDownLatch,
    private val gate: CompletableDeferred<Unit>,
) : MediaPreviewSource {

    override suspend fun preview(file: MediaFile): MediaPreview = visiblePreview(file, 0)

    override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
        entered.countDown()
        gate.await()
        return MediaPreview(image = null, durationMillis = index + 1L)
    }

    override suspend fun prefetch(file: MediaFile): MediaPreview = preview(file)

    override fun updatePreviewWindow(visibleLocations: Set<String>, prefetchLocations: Set<String>) = Unit
}

/** 记录抽图开始顺序的抽取器，用来断言一屏可见项的出图顺序。 */
private class OrderRecordingExtractor : MediaPreviewExtractor {

    val started = mutableListOf<Int>()

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation =
        NetworkPreviewPreparation(
            decodeBlock = { targetWidth -> imageBytes(file, targetWidth) },
            closeBlock = {},
        )

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        started += file.name.removePrefix("第").substringBefore(' ').toInt()
        return byteArrayOf(1)
    }

    override suspend fun durationMillis(file: MediaFile): Long? = null
}

/** 记录下发与取消的假预览源；[gate] 非空时压住抽取，用来构造滚动与重置。 */
private class SchedulerRecordingSource : MediaPreviewSource {

    val started = mutableListOf<Int>()
    val cancelled = mutableListOf<Int>()
    val cancelReasons = mutableListOf<String>()
    val windows = ConcurrentHashMap<String, Set<String>>()

    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null

    /** 已进入解码的位置：这些条目暂停时不取消，跑完再收尾。 */
    var extractingLocations: Set<String> = emptySet()

    override fun isPreviewExtracting(file: MediaFile): Boolean =
        file.location in extractingLocations

    override suspend fun preview(file: MediaFile): MediaPreview = visiblePreview(file, 0)

    override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview {
        started += index
        failure?.let { problem -> throw problem }
        try {
            gate?.await()
        } catch (cancellation: CancellationException) {
            cancelled += index
            cancelReasons += generateSequence<Throwable>(cancellation) { error -> error.cause }
                .mapNotNull { error -> error.message }
                .firstOrNull { message -> message == "离屏" || message == "列表重建" || message == "换源" || message == "订阅取消" }
                ?: "取消"
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
