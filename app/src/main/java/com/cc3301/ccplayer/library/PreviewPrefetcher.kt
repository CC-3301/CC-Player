package com.cc3301.ccplayer.library

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 目录预览的预取调度：只预取可见窗口前后各一行的文件，延迟发起，让可见项先请求；
 * 窗口变化时取消离开范围的请求，快速滚动不堆积。
 */
class PreviewPrefetcher(
    private val source: MediaPreviewSource,
    private val scope: CoroutineScope,
    private val prefetchDelayMillis: Long = PREFETCH_DELAY_MILLIS,
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    /** 按当前可见范围更新预取；[rowSize] 为一行包含的条目数。 */
    fun update(entries: List<MediaFile>, firstVisible: Int, lastVisible: Int, rowSize: Int) {
        val wanted = prefetchEntries(entries, firstVisible, lastVisible, rowSize)
        val wantedLocations = wanted.mapTo(HashSet(), MediaFile::location)
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val (location, job) = iterator.next()
            if (location !in wantedLocations) {
                job.cancel()
                iterator.remove()
            }
        }
        for (entry in wanted) {
            if (jobs.containsKey(entry.location)) continue
            val job = scope.launch {
                delay(prefetchDelayMillis)
                source.preview(entry)
            }
            jobs[entry.location] = job
            job.invokeOnCompletion { jobs.remove(entry.location, job) }
        }
    }

    /** 离开库页面时取消全部预取。 */
    fun cancelAll() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    private companion object {
        /** 预取前等待，让可见项的请求先占并发名额。 */
        const val PREFETCH_DELAY_MILLIS = 120L
    }
}

/** 可见窗口前后各一行中需要预取的条目；子目录跳过。 */
internal fun prefetchEntries(
    entries: List<MediaFile>,
    firstVisible: Int,
    lastVisible: Int,
    rowSize: Int,
): List<MediaFile> {
    if (entries.isEmpty() || rowSize <= 0 || firstVisible < 0 || lastVisible < firstVisible) return emptyList()
    val start = maxOf(0, firstVisible - rowSize)
    val end = minOf(entries.lastIndex, lastVisible + rowSize)
    return (start..end)
        .filter { index -> index < firstVisible || index > lastVisible }
        .mapNotNull { index -> entries.getOrNull(index) }
        .filterNot(MediaFile::isDirectory)
}
