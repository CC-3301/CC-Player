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
 * 条目再次可见时续用同一个 deferred 重新下发，避免出现永久占位。
 * 预取仍由 [prefetcher] 负责，可见请求优先于预取，且预取不进入可见请求队列。
 *
 * 状态不变量：
 * 1. 每个位置只有一个 [Request]，保存订阅数、attempt 令牌、Job 与 deferred。
 * 2. 每次下发先递增 [Request.attemptId]，创建 LAZY Job 并登记到 [Request.job]，再启动。
 * 3. 完成回调必须同时校验 attempt 令牌与 Job 身份；旧 attempt 不得完成 deferred、
 *    不得移除请求，也不得取消新 Job。
 * 4. 离屏、空窗口与 [reset] 只作废当前 attempt 并取消 Job，保留请求与 deferred；
 *    再次可见时重新下发。
 * 5. 只有最后一个订阅者取消时才取消（异常完成）deferred 并移除请求。
 * 6. [cancelAll] 先在锁内快照并作废全部 attempt，再在锁外逐个取消 Job，
 *    完成回调不会遍历正在被修改的集合。
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

    /** 更新可见与预取范围；离屏请求只作废当前 attempt，保留订阅等待再次可见。空范围同样生效。 */
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
                existing.subscribers++
                existing
            } else {
                Request(entry, index, CompletableDeferred()).also { created ->
                    created.subscribers = 1
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
                request.subscribers--
                // 只有最后一个订阅者取消时才移除；晚到的新请求不会被旧请求的清理误删。
                if (cancelled && request.subscribers <= 0) removeRequestLocked(request, cancelDeferred = true)
            }
        }
    }

    /** 切换目录或视图时清空窗口并作废全部在途 attempt；订阅保留，后续可见时续订。 */
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
        val pending = synchronized(lock) {
            closed = true
            visibleLocations = emptySet()
            windowKnown = true
            dispatching = false
            val snapshot = requests.values.map { request ->
                invalidateAttemptLocked(request) to request
            }
            requests.clear()
            snapshot
        }
        // 先快照并作废 attempt，再在锁外取消：完成回调只会校验令牌后返回。
        pending.forEach { (job, request) ->
            job?.cancel()
            if (!request.deferred.isCompleted) request.deferred.complete(EMPTY_PREVIEW)
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

    /** 作废当前 attempt 并取消 Job；请求与 deferred 保留，等再次可见时重新下发。 */
    private fun pauseLocked(request: Request) {
        invalidateAttemptLocked(request)?.cancel()
    }

    /** 作废当前 attempt，返回被取消的 Job；回调随后会因令牌或 Job 身份不符而退出。 */
    private fun invalidateAttemptLocked(request: Request): Job? {
        request.attemptId++
        val job = request.job
        request.job = null
        return job
    }

    private fun removeRequestLocked(request: Request, cancelDeferred: Boolean) {
        if (requests[request.file.location] === request) requests.remove(request.file.location)
        invalidateAttemptLocked(request)?.cancel()
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
            val attempt = synchronized(lock) {
                if (!windowKnown || visibleLocations.isEmpty()) return
                val next = requests.values
                    .filter { request -> request.job == null && request.file.location in visibleLocations }
                    .minByOrNull(Request::index)
                    ?: return
                val attemptId = ++next.attemptId
                // 先建 Job 并登记，再启动：reset/pause 在两步之间也能取消到它。
                val job = scope.launch(start = CoroutineStart.LAZY) { runRequest(next, attemptId) }
                next.job = job
                Attempt(next, attemptId, job)
            }
            attempt.job.invokeOnCompletion { finishAttempt(attempt) }
            attempt.job.start()
        }
    }

    private suspend fun runRequest(request: Request, attemptId: Long) {
        val preview = try {
            source.visiblePreview(request.file, request.index)
        } catch (cancellation: CancellationException) {
            // 窗口暂停或订阅取消时不回填占位图；再次可见会重新下发。
            throw cancellation
        } catch (failure: Throwable) {
            // Error 也按失败收尾，避免 attempt 卡住、结果永不完成。
            EMPTY_PREVIEW
        }
        synchronized(lock) {
            if (!isCurrentAttemptLocked(request, attemptId)) return
            // 先在锁内完成并移除，避免完成与暂停之间留下已完成的请求。
            request.attemptId++
            request.job = null
            if (requests[request.file.location] === request) requests.remove(request.file.location)
            if (!request.deferred.isCompleted) request.deferred.complete(preview)
        }
    }

    /** 取消或未正常返回的 attempt 在这里收尾；令牌或 Job 身份不符时不得改状态。 */
    private fun finishAttempt(attempt: Attempt) {
        val request = attempt.request
        synchronized(lock) {
            if (!isCurrentAttemptLocked(request, attempt.attemptId)) return
            if (request.job !== attempt.job) return
            request.attemptId++
            request.job = null
            if (requests[request.file.location] === request) requests.remove(request.file.location)
            if (!request.deferred.isCompleted) request.deferred.complete(EMPTY_PREVIEW)
        }
    }

    private fun isCurrentAttemptLocked(request: Request, attemptId: Long): Boolean =
        !request.deferred.isCompleted && request.attemptId == attemptId

    private fun hasDispatchableLocked(): Boolean =
        visibleLocations.isNotEmpty() &&
            requests.values.any { request -> request.job == null && request.file.location in visibleLocations }

    private class Request(
        val file: MediaFile,
        var index: Int,
        val deferred: CompletableDeferred<MediaPreview>,
        var subscribers: Int = 0,
        var attemptId: Long = 0,
        var job: Job? = null,
    )

    private class Attempt(
        val request: Request,
        val attemptId: Long,
        val job: Job,
    )

    private companion object {
        val EMPTY_PREVIEW = MediaPreview(image = null, durationMillis = null)
    }
}
