package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 字节预读窗口上限：每条目随解码位置预读的字节范围。 */
internal const val PREVIEW_READ_WINDOW_BYTES = 8L * 1024 * 1024

/** 字节块粒度：一次底层读取的字节数，窗口上限是它的整数倍。 */
internal const val PREVIEW_READ_CHUNK_BYTES = 256 * 1024

/**
 * 本地字节窗口：解码只读本地字节，缺失的块由本层向底层补齐。
 *
 * 同一时刻最多两路底层读取：解码线程读当前块，后台预读线程读后续块。
 * 预读锚点跟着解码位置走，跳到别处时重置并只预读新位置之后的窗口，不从头重读；
 * 常驻内存只保留锚点窗口内的块，锚点移动时淘汰窗口外的块，窗口到文件末尾时按实际长度收口。
 * 关闭后不再补齐、不再回写，读取一律失败。预读失败不在调用方之外扩散，由下一次读取暴露。
 */
internal class PreviewByteWindow(
    private val origin: ByteSource,
    private val windowBytes: Long = PREVIEW_READ_WINDOW_BYTES,
    private val chunkBytes: Int = PREVIEW_READ_CHUNK_BYTES,
    private val readAheadExecutor: Executor = previewReadAheadExecutor,
) : ByteSource {

    private val lock = Object()

    /** 已拉进本地的块，按起点索引。 */
    private val chunks = HashMap<Long, ByteArray>()

    /** 正在向底层读的块起点；同一块只读一次。 */
    private val pending = HashSet<Long>()

    /** 读失败的块；重复读取按同一失败返回。 */
    private val failures = HashMap<Long, IOException>()

    /** 已排队但尚未开始的预读块。 */
    private val queued = LinkedHashSet<Long>()

    /** 预读锚点：最近一次解码读取所在的块起点。 */
    private var anchor = -1L

    /** 最近一次触发预读的块起点，块内重复读取不重复挂预读。 */
    private var lastPrefetchChunk = -1L

    private var closed = false

    override val size: Long get() = origin.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        val total = size
        if (position < 0 || position >= total) return 0
        val start = chunkStart(position)
        val bytes = chunkAt(start)
        val from = (position - start).toInt()
        if (from >= bytes.size) return 0
        val count = minOf(length, bytes.size - from)
        System.arraycopy(bytes, from, buffer, offset, count)
        prefetchAfter(start)
        return count
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            queued.clear()
            pending.clear()
            chunks.clear()
            lock.notifyAll()
        }
        runCatching { origin.close() }
    }

    /** 取块内容；本块缺失时由当前线程补齐，补齐期间其它线程等锁上的通知。 */
    private fun chunkAt(start: Long): ByteArray {
        synchronized(lock) {
            while (true) {
                // 关闭优先于一切：已关闭时本地有块也不返回。
                if (closed) throw IOException("预览读取已取消")
                chunks[start]?.let { return it }
                failures[start]?.let { throw it }
                if (start in pending) {
                    lock.wait()
                } else {
                    pending += start
                    break
                }
            }
        }
        val result = runCatching { load(start) }
        var closedWhileLoading = false
        synchronized(lock) {
            pending -= start
            closedWhileLoading = closed
            if (!closedWhileLoading) {
                result.fold(
                    onSuccess = { bytes -> chunks[start] = bytes },
                    onFailure = { failure -> failures[start] = failure.asPreviewReadFailure() },
                )
            }
            lock.notifyAll()
        }
        if (closedWhileLoading) throw IOException("预览读取已取消")
        return result.getOrThrow()
    }

    /** 从底层补齐一块：文件剩不下整块时按实际长度收口，读不到字节时给空块。 */
    private fun load(start: Long): ByteArray {
        val limit = minOf(chunkBytes.toLong(), size - start).toInt()
        if (limit <= 0) return ByteArray(0)
        val bytes = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val count = origin.readAt(start + read, bytes, read, limit - read)
            if (count <= 0) break
            read += count
        }
        // 读不到字节（读到底或被关闭）按文件末尾处理，不当作失败扩散。
        return if (read == limit) bytes else bytes.copyOf(read)
    }

    /** 块变化时重置预读锚点：只预读当前位置之后的窗口，不回头读已经走过的字节。 */
    private fun prefetchAfter(start: Long) {
        val from = start + chunkBytes
        synchronized(lock) {
            if (closed || lastPrefetchChunk == start) return
            lastPrefetchChunk = start
            anchor = from
            // 只保留锚点窗口内的块：跨远距读取不会把走过的字节一直留在内存里。
            chunks.keys.removeAll { chunk -> !isRetainedLocked(chunk) }
            queued.clear()
            var next = from
            val end = minOf(size, from + windowBytes)
            while (next < end) {
                if (chunks[next] == null && next !in pending) queued += next
                next += chunkBytes
            }
            if (queued.isEmpty()) return
            val tasks = queued.toList()
            tasks.forEach { task -> readAheadExecutor.execute { readAhead(task) } }
        }
    }

    /** 后台预读一块；锚点已被重置、块已就绪或正在读取时直接退出。 */
    private fun readAhead(start: Long) {
        synchronized(lock) {
            if (!queued.remove(start)) return
            if (closed || chunks[start] != null || start in pending) return
            if (!isRetainedLocked(start)) return
            pending += start
        }
        val result = runCatching { load(start) }
        synchronized(lock) {
            pending -= start
            // 关闭后、或读取期间锚点已移走：补齐的块不回写，避免把淘汰掉的块又留回内存。
            if (!closed && isRetainedLocked(start)) {
                result.onSuccess { bytes -> chunks[start] = bytes }
                result.onFailure { failure -> failures[start] = failure.asPreviewReadFailure() }
            }
            lock.notifyAll()
        }
    }

    /** 块是否仍落在锚点窗口内；常驻内存只保留这一段的块。 */
    private fun isRetainedLocked(start: Long): Boolean =
        start >= anchor - chunkBytes && start < anchor + windowBytes

    private fun chunkStart(position: Long): Long = position - position % chunkBytes

    private fun Throwable.asPreviewReadFailure(): IOException =
        this as? IOException ?: IOException("预览字节读取失败", this)

    private companion object {
        /** 进程内唯一的预读线程：与解码线程合起来正好两路底层读取。 */
        val previewReadAheadExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "preview-byte-read-ahead").apply { isDaemon = true }
        }
    }
}
