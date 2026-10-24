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
 * 可见请求按列表索引从上到下下发；快速滚动后离屏的排队请求与在途请求都会取消。
 * 预取仍由 [prefetcher] 负责，可见请求优先于预取，且预取不进入可见请求队列。
 * 每个条目只订阅自己那一项的结果。
 */
class VisiblePreviewScheduler(
    private val source: MediaPreviewSource,
    private val scope: CoroutineScope,
    private val prefetcher: PreviewPrefetcher = PreviewPrefetcher(source, scope),
) {

    private val lock = Any()

    /** 从下发到完成之间的请求；完成后移出。 */
    private val requests = LinkedHashMap<String, Request>()
    private val jobs = HashMap<String, Job>()

    private var visibleLocations: Set<String> = emptySet()

    /** 是否已经收到过窗口；没有窗口时先排队，等界面给出可见范围再下发。 */
    private var windowKnown = false

    private var dispatching = false

    /** 离开库页面后不再接受新请求。 */
    private var closed = false

    /** 更新可见与预取范围；离屏请求取消，不消耗抽取。空范围同样生效。 */
    fun update(entries: List<MediaFile>, firstVisible: Int, lastVisible: Int, rowSize: Int) {
        val visible = if (firstVisible >= 0 && lastVisible >= firstVisible) {
            (firstVisible..lastVisible).mapNotNullTo(HashSet()) { index -> entries.getOrNull(index)?.location }
        } else {
            emptySet()
        }
        synchronized(lock) {
            windowKnown = true
            visibleLocations = visible
            cancelOutsideLocked(visible)
        }
        prefetcher.update(entries, firstVisible, lastVisible, rowSize)
        dispatch()
    }

    /**
     * 订阅一个条目的预览。请求先入队，是否下发由当前窗口决定：
     * 新入屏条目可能先调用这里、后收到 [update]，因此不能按旧窗口直接拒绝。
     */
    suspend fun preview(entry: MediaFile, index: Int): MediaPreview {
        if (entry.isDirectory) return MediaPreview(image = null, durationMillis = null)
        val request = synchronized(lock) {
            if (closed) return EMPTY_PREVIEW
            requests[entry.location]?.also { existing -> existing.index = index }
                ?: Request(entry, index, CompletableDeferred()).also { created ->
                    requests[created.file.location] = created
                }
        }
        dispatch()
        return request.deferred.await()
    }

    /** 切换目录或视图时清空旧窗口与旧请求，保留调度器可继续使用。 */
    fun reset() {
        synchronized(lock) {
            closed = false
            cancelAllLocked()
        }
        prefetcher.cancelAll()
    }

    /** 离开库页面时取消全部请求与预取，并清空可见范围。 */
    fun cancelAll() {
        synchronized(lock) {
            closed = true
            cancelAllLocked()
        }
        prefetcher.cancelAll()
    }

    private fun cancelAllLocked() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        requests.values.forEach { request -> request.deferred.complete(EMPTY_PREVIEW) }
        requests.clear()
        visibleLocations = emptySet()
        windowKnown = true
        dispatching = false
    }

    private fun cancelOutsideLocked(visible: Set<String>) {
        val iterator = requests.iterator()
        while (iterator.hasNext()) {
            val (location, request) = iterator.next()
            if (location in visible) continue
            iterator.remove()
            jobs.remove(location)?.cancel()
            request.deferred.complete(EMPTY_PREVIEW)
        }
        val jobIterator = jobs.iterator()
        while (jobIterator.hasNext()) {
            val (location, job) = jobIterator.next()
            if (location in visible) continue
            jobIterator.remove()
            job.cancel()
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
            val request = synchronized(lock) {
                if (!windowKnown || visibleLocations.isEmpty()) return
                val next = requests.values
                    .filter { pending -> !pending.started && pending.file.location in visibleLocations }
                    .minByOrNull(Request::index)
                    ?: return
                next.started = true
                next
            }
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val preview = try {
                    source.visiblePreview(request.file, request.index)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    EMPTY_PREVIEW
                }
                request.deferred.complete(preview)
            }
            synchronized(lock) {
                if (job.isCompleted) {
                    requests.remove(request.file.location)
                } else {
                    jobs[request.file.location] = job
                }
            }
            job.invokeOnCompletion {
                synchronized(lock) {
                    jobs.remove(request.file.location, job)
                    requests.remove(request.file.location)
                }
                request.deferred.complete(EMPTY_PREVIEW)
            }
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
    )

    private companion object {
        val EMPTY_PREVIEW = MediaPreview(image = null, durationMillis = null)
    }
}
