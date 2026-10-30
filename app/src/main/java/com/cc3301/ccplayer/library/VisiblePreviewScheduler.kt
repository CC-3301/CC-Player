package com.cc3301.ccplayer.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * 可见请求调度器：浏览页与缩略图源之间的调度层。
 *
 * 整屏可见请求一次性下发入队，出图顺序由抽帧许可按列表索引决定（可见按索引 < 预取 < 时长补读）。
 * 失败项先跳过、不占队列：可见项出完后按 1s、2s、3s、4s、5s 递进重试，单个最多 5 次。
 * 离屏只暂停抽取、保留待调度请求；条目再次可见时续用同一个 deferred 重新下发，避免出现永久占位。
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
 * 6. [cancelAll] 先在锁内快照并作废全部 attempt 与重试计时，再在锁外逐个取消 Job，
 *    完成回调不会遍历正在被修改的集合。
 * 7. 离屏、空窗口与 [reset] 取消重试计时但保留待重试标记；再次可见时按当前间隔重新计时。
 * 8. 重试计时由 attempt 收尾与订阅收尾直接安排，不依赖 [dispatch] 的收尾分支；
 *    等批次收尾由信号唤醒，不轮询。
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

    /** 下发批次的代号；[cancelAll] 递增即作废仍在运行的旧批次。 */
    private var drainGeneration = 0L

    /** 离开库页面后不再接受新请求。 */
    private var closed = false

    /** 一批首次尝试收尾的信号；重试计时在它上面等，不做轮询。 */
    private var batchSettled = CompletableDeferred<Unit>()

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
        wakeRetryWaiters()
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
            // 移除会让首轮项消失：重试计时与等批次的计时器要跟着重新评估，不能只靠 dispatch 的收尾分支。
            resumeAfterAttempt()
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
        wakeRetryWaiters()
    }

    /** 离开库页面时取消全部请求与预取，并清空可见范围。 */
    fun cancelAll() {
        val pending = synchronized(lock) {
            closed = true
            visibleLocations = emptySet()
            windowKnown = true
            dispatching = false
            // 作废旧批次：它与新批次并跑会打乱下发顺序。
            drainGeneration++
            val snapshot = requests.values.map { request ->
                val timer = request.retryTimer
                request.retryTimer = null
                request.retryPending = false
                PausedAttempt(invalidateAttemptLocked(request), timer, request)
            }
            requests.clear()
            snapshot
        }
        // 先快照并作废 attempt 与重试计时，再在锁外取消：完成回调只会校验令牌后返回。
        pending.forEach { paused ->
            paused.attempt?.cancel()
            paused.timer?.cancel()
            val request = paused.request
            if (!request.deferred.isCompleted) request.deferred.complete(EMPTY_PREVIEW)
        }
        prefetcher.cancelAll()
        wakeRetryWaiters()
    }

    private fun pauseOutsideLocked(visible: Set<String>) {
        requests.values
            .filter { request -> request.file.location !in visible }
            .forEach(::pauseLocked)
    }

    private fun pauseAllLocked() {
        requests.values.forEach(::pauseLocked)
    }

    /**
     * 作废当前 attempt 并取消 Job；离屏也不再跑重试计时，但保留待重试标记。
     * 再次可见时重新按当前间隔计时，不会回到可见就 0 秒重试。
     */
    private fun pauseLocked(request: Request) {
        invalidateAttemptLocked(request)?.cancel()
        request.retryTimer?.cancel()
        request.retryTimer = null
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
        request.retryTimer?.cancel()
        request.retryTimer = null
        request.retryPending = false
        if (cancelDeferred && !request.deferred.isCompleted) {
            request.deferred.cancel(CancellationException("预览订阅已取消"))
        }
    }

    private fun dispatch() {
        val generation: Long
        synchronized(lock) {
            if (dispatching) return
            dispatching = true
            generation = ++drainGeneration
        }
        scope.launch {
            try {
                // 让同一轮组合中的可见条目都先入队，再按索引排序下发。
                yield()
                drain(generation)
            } finally {
                val again = synchronized(lock) {
                    // 已被 cancelAll 作废的 drain 不得复位标志，也不得再发起新一轮。
                    if (generation != drainGeneration) return@synchronized false
                    dispatching = false
                    hasDispatchableLocked()
                }
                if (again) {
                    dispatch()
                } else {
                    // 可见项都出完（或都失败待重试）才安排失败项的重试。
                    scheduleRetries()
                    wakeRetryWaiters()
                }
            }
        }
    }

    /**
     * 整屏可见项一次性下发入队，顺序交给抽帧许可按列表索引决定。
     * 不在这里等前一条收尾：许可释放时若还在等，正在排队的预取会先拿到许可，
     * 可见项要多等一次完整的预取解码；许可空闲与已发出的许可都由许可自身按索引排序。
     */
    private suspend fun drain(generation: Long) {
        while (true) {
            val attempt = synchronized(lock) {
                if (generation != drainGeneration) return
                if (!windowKnown || visibleLocations.isEmpty()) return
                val next = nextDispatchableLocked() ?: return
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
            request.attemptId++
            request.job = null
            if (preview.retryable && request.retryAttempts < VISIBLE_RETRY_MAX_ATTEMPTS) {
                // 失败项先跳过：保留 deferred 与订阅，等可见项全部出图后再按递进间隔重试。
                request.retryAttempts++
                request.retryPending = true
            } else {
                // 先在锁内完成并移除，避免完成与暂停之间留下已完成的请求。
                if (requests[request.file.location] === request) requests.remove(request.file.location)
                if (!request.deferred.isCompleted) request.deferred.complete(preview)
            }
        }
        // 重新走一轮下发，并独立安排重试计时：失败项要靠这一轮排空后才轮到重试。
        resumeAfterAttempt()
    }

    /**
     * 下一项要下发的请求：先按索引发首次尝试的可见项；全部出图后（没有首次尝试在跑）
     * 才轮到失败项的重试，重试不得越过仍在等出图的可见项。
     */
    private fun nextDispatchableLocked(): Request? {
        val candidates = requests.values.filter { request ->
            request.isDispatchableLocked() && request.file.location in visibleLocations
        }
        if (candidates.isEmpty()) return null
        val fresh = candidates.filter { it.retryAttempts == 0 }
        if (fresh.isNotEmpty()) return fresh.minByOrNull(Request::index)
        val freshRunning = requests.values.any { request ->
            request.job != null && request.retryAttempts == 0 && request.file.location in visibleLocations
        }
        return if (freshRunning) null else candidates.minByOrNull(Request::index)
    }

    /**
     * 一次 attempt 收尾后的统一续跑：推进下发、安排重试计时、唤醒等批次的计时器。
     * 三件事各走各的路，attempt 从别处启动也不会丢重试计时。
     */
    private fun resumeAfterAttempt() {
        dispatch()
        scheduleRetries()
        wakeRetryWaiters()
    }

    /** 还有首次尝试没出图的可见项：在跑，或可下发。 */
    private fun hasFreshWorkLocked(): Boolean = requests.values.any { request ->
        request.retryAttempts == 0 &&
            request.file.location in visibleLocations &&
            (request.job != null || request.isDispatchableLocked())
    }

    /** 唤醒等批次收尾的重试计时；醒来后各自复查可见性。 */
    private fun wakeRetryWaiters() {
        val gate = synchronized(lock) { batchSettled.also { batchSettled = CompletableDeferred() } }
        gate.complete(Unit)
    }

    /** 可见项全部出图后为每个失败项安排重试计时：第 n 次重试等 n 秒。 */
    private fun scheduleRetries() {
        val starters = ArrayList<Pair<Request, Job>>()
        synchronized(lock) {
            if (closed) return
            // 还有可见项没出图（在跑或可下发）时不安排重试。
            if (hasFreshWorkLocked()) return
            requests.values
                .filter { request ->
                    request.retryPending && request.retryTimer == null && request.file.location in visibleLocations
                }
                .forEach { request ->
                    val job = scope.launch(start = CoroutineStart.LAZY) { awaitRetry(request) }
                    request.retryTimer = job
                    starters += request to job
                }
        }
        starters.forEach { (request, job) ->
            job.invokeOnCompletion {
                synchronized(lock) { if (request.retryTimer === job) request.retryTimer = null }
            }
            job.start()
        }
    }

    /** 等满递进间隔后把失败项重新排上；期间离屏或取消就不再重试。 */
    private suspend fun awaitRetry(request: Request) {
        // 间隔在锁内取：重试次数只由下发线程在锁内改。
        val step = synchronized(lock) {
            if (closed || request.deferred.isCompleted) return
            request.retryAttempts
        }
        delay(VISIBLE_RETRY_STEP_MILLIS * step)
        // 等可见项全部出图：还有首次尝试在排队或在下发时不得插到它前面。批次收尾会唤醒，不轮询。
        while (true) {
            val gate = synchronized(lock) {
                if (closed || request.deferred.isCompleted) return
                if (hasFreshWorkLocked()) batchSettled else null
            } ?: break
            gate.await()
        }
        val again = synchronized(lock) {
            if (closed || request.deferred.isCompleted) return
            request.retryPending = false
            request.file.location in visibleLocations
        }
        if (again) dispatch()
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
        resumeAfterAttempt()
    }

    private fun isCurrentAttemptLocked(request: Request, attemptId: Long): Boolean =
        !request.deferred.isCompleted && request.attemptId == attemptId

    private fun hasDispatchableLocked(): Boolean =
        visibleLocations.isNotEmpty() && nextDispatchableLocked() != null

    private class Request(
        val file: MediaFile,
        var index: Int,
        val deferred: CompletableDeferred<MediaPreview>,
        var subscribers: Int = 0,
        var attemptId: Long = 0,
        var job: Job? = null,
        /** 已安排的失败重试次数，上限 [VISIBLE_RETRY_MAX_ATTEMPTS]。 */
        var retryAttempts: Int = 0,
        /** 正在等重试计时；期间不参与下发。 */
        var retryPending: Boolean = false,
        /** 正在跑的重试计时任务。 */
        var retryTimer: Job? = null,
    ) {

        /** 没有在跑的 attempt，也没有在等重试计时，才算可下发。 */
        fun isDispatchableLocked(): Boolean = job == null && !retryPending
    }

    private class Attempt(
        val request: Request,
        val attemptId: Long,
        val job: Job,
    )

    /** 一次暂停快照：attempt Job、重试计时 Job 与请求本身。 */
    private class PausedAttempt(
        val attempt: Job?,
        val timer: Job?,
        val request: Request,
    )

    private companion object {
        val EMPTY_PREVIEW = MediaPreview(image = null, durationMillis = null)

        /** 失败重试的递进步长：第 n 次重试等 n 秒。 */
        const val VISIBLE_RETRY_STEP_MILLIS = 1_000L

        /** 单个条目的失败重试上限。 */
        const val VISIBLE_RETRY_MAX_ATTEMPTS = 5
    }
}
