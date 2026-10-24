package com.cc3301.ccplayer.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * 可见请求调度器：浏览页与缩略图源之间的调度层。
 *
 * 可见请求按列表索引从上到下下发。离屏只暂停抽取、保留待调度请求；
 * 条目再次可见时续用同一结果，避免出现永久占位。订阅方取消时才移除请求。
 * 预取仍由 [prefetcher] 负责，可见请求优先于预取，且预取不进入可见请求队列。
 */
class VisiblePreviewScheduler(
    private val source: MediaPreviewSource,
    private val scope: CoroutineScope,
    private val prefetcher: PreviewPrefetcher = PreviewPrefetcher(source, scope),
) {

    private val lock = Any()

    /** 同一位置的请求；条目只订阅自己那一项的结果。 */
    private val requests = LinkedHashMap<String, Request>()

    private var visibleLocations: Set<String> = emptySet()

    /** 是否已经收到过窗口；没有窗口时先排队，等界面给出可见范围再下发。 */
    private var windowKnown = false

    private var dispatching = false

    /** 离开库页面后不再接受新请求。 */
    private var closed = false

    /** 更新可见与预取范围；离屏请求只暂停抽取，保留订阅等待再次可见。空范围同样生效。 */
    fun update(entries: List<MediaFile>, firstVisible: Int, lastVisible: Int, rowSize: Int) {
        val visible = if (firstVisible >= 0 && lastVisible >= firstVisible) {
            (firstVisible..lastVisible).mapNotNullTo(HashSet()) { index -> entries.getOrNull(index)?.location }
        } else {
            emptySet()
        }
        synchronized(lock) {
            windowKnown = true
            visibleLocations = visible
            pauseOutsideLocked(visible)
        }
        prefetcher.update(entries, firstVisible, lastVisible, rowSize)
        dispatch()
    }

    /**
     * 订阅一个条目的预览。请求先入队，是否下发由当前窗口决定：
     * 新入屏条目可能先调用这里、后收到 [update]，因此不能按旧窗口直接拒绝。
     */
    suspend fun preview(entry: MediaFile, index: Int): MediaPreview {
        if (entry.isDirectory) return EMPTY_PREVIEW
        val request = synchronized(lock) {
            if (closed) return EMPTY_PREVIEW
            val existing = requests[entry.location]
            if (existing != null) {
                existing.index = index
                existing.waiters++
                existing
            } else {
                Request(entry, index, CompletableDeferred()).also { created ->
                    created.waiters = 1
                    requests[created.file.location] = created
                }
            }
        }
        dispatch()
        var cancelled = false
        try {
            return request.deferred.await()
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } finally {
            synchronized(lock) {
                request.waiters--
                // 只有最后一个订阅者取消时才移除；晚到的新请求不会被旧请求的清理误删。
                if (cancelled && request.waiters <= 0) removeRequestLocked(request, cancelDeferred = true)
            }
        }
    }

    /** 切换目录或视图时清空窗口并暂停全部在途抽取；订阅保留，后续可见时续订。 */
    fun reset() {
        synchronized(lock) {
            closed = false
            visibleLocations = emptySet()
            windowKnown = true
            pauseAllLocked()
        }
        prefetcher.cancelAll()
    }

    /** 离开库页面时取消全部请求与预取，并清空可见范围。 */
    fun cancelAll() {
        synchronized(lock) {
            closed = true
            requests.values.forEach { request ->
                request.job?.cancel()
                request.job = null
                request.deferred.complete(EMPTY_PREVIEW)
            }
            requests.clear()
            visibleLocations = emptySet()
            windowKnown = true
            dispatching = false
        }
        prefetcher.cancelAll()
    }

    private fun pauseOutsideLocked(visible: Set<String>) {
        requests.values
            .filter { request -> request.file.location !in visible }
            .forEach(::pauseLocked)
    }

    private fun pauseAllLocked() {
        requests.values.forEach(::pauseLocked)
    }

    private fun pauseLocked(request: Request) {
        request.started = false
        request.pausedByWindow = true
        request.job?.cancel()
        request.job = null
    }

    private fun removeRequestLocked(request: Request, cancelDeferred: Boolean) {
        val location = request.file.location
        if (requests[location] === request) requests.remove(location)
        request.job?.cancel()
        request.job = null
        if (cancelDeferred && !request.deferred.isCompleted) {
            request.deferred.cancel(CancellationException("预览订阅已取消"))
        }
    }

    private fun dispatch() {
        synchronized(lock) {
            if (dispatching) return
            dispatching = true
        }
        scope.launch {
            try {
                // 让同一轮组合中的可见条目都先入队，再按索引排序下发。
                yield()
                drain()
            } finally {
                val again = synchronized(lock) {
                    dispatching = false
                    hasDispatchableLocked()
                }
                if (again) dispatch()
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val (request, job) = synchronized(lock) {
                if (!windowKnown || visibleLocations.isEmpty()) return
                val next = requests.values
                    .filter { pending -> !pending.started && pending.file.location in visibleLocations }
                    .minByOrNull(Request::index)
                    ?: return
                next.started = true
                next.pausedByWindow = false
                // 先建 Job 并登记，再启动：reset/pause 在两步之间也能取消到它。
                val created = scope.launch(start = CoroutineStart.LAZY) { runRequest(next) }
                next.job = created
                next to created
            }
            job.invokeOnCompletion { finishRequest(request, job) }
            job.start()
        }
    }

    private suspend fun runRequest(request: Request) {
        val preview = try {
            source.visiblePreview(request.file, request.index)
        } catch (cancellation: CancellationException) {
            // 窗口暂停或订阅取消时不回填占位图；再次可见会重新下发。
            throw cancellation
        } catch (failure: Throwable) {
            // Error 也按失败收尾，避免 started 卡住、结果永不完成。
            EMPTY_PREVIEW
        }
        synchronized(lock) {
            if (!request.pausedByWindow) request.deferred.complete(preview)
        }
    }

    /** 正常完成、Error 或取消都从这里收尾；窗口暂停的请求保留，等再次可见续订。 */
    private fun finishRequest(request: Request, job: Job) {
        synchronized(lock) {
            if (request.job === job) request.job = null
            if (!request.pausedByWindow && !request.deferred.isCompleted) {
                request.deferred.complete(EMPTY_PREVIEW)
            }
            if (request.deferred.isCompleted) removeRequestLocked(request, cancelDeferred = false)
        }
    }

    private fun hasDispatchableLocked(): Boolean =
        visibleLocations.isNotEmpty() &&
            requests.values.any { request -> !request.started && request.file.location in visibleLocations }

    private class Request(
        val file: MediaFile,
        var index: Int,
        val deferred: CompletableDeferred<MediaPreview>,
        var started: Boolean = false,
        var pausedByWindow: Boolean = false,
        var job: Job? = null,
        var waiters: Int = 0,
    )

    private companion object {
        val EMPTY_PREVIEW = MediaPreview(image = null, durationMillis = null)
    }
}
