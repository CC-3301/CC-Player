package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource
import java.io.IOException

/** 字节预读窗口上限：每条目常驻内存的字节上限。 */
internal const val PREVIEW_READ_WINDOW_BYTES = 8L * 1024 * 1024

/** 读取块上限：一次补齐的字节数；底层协商上限更小时按协商上限收窄。 */
internal const val PREVIEW_READ_CHUNK_BYTES = 256 * 1024

/** 元数据阶段的头部定点读：容器头在文件开头。 */
internal const val PREVIEW_METADATA_HEAD_BYTES = 64 * 1024

/** 元数据阶段的尾部定点读：容器索引通常在文件末尾。 */
internal const val PREVIEW_METADATA_TAIL_BYTES = 1024 * 1024

/** 解码命中一块后向后扩 1 块。 */
private const val FOLLOW_READ_AHEAD_CHUNKS = 1

/**
 * 本地字节窗口：解码只读本地字节，缺失的块由本层向底层补齐。
 *
 * 补齐按块进行：块大小取读取块上限与底层协商上限中较小者，一块正好一次底层读取。
 * 元数据阶段只定点取文件头 64 KiB 与文件末尾 1 MiB，不铺满整窗；解码命中一块后向后扩 1 块。
 * 换锚点不丢弃已读到的块：常驻块按最近使用淘汰，总量不超过窗口上限。
 * 预读交给进程内一条线程的池，每次提交带本条目的 rank：可见项按列表索引，预取排在可见之后，
 * 已经在跑的读取不抢占。跨条目也串行，同一时刻只有一块在读。
 * 预读可用独立字节源，避免与解码在同一条流上交替位置。关闭后不再补齐、不再回写，读取一律失败。
 *
 * 已知限制：预读按块串行，一次只读一块；
 * 解码缺块仍由解码线程在许可内补齐（预读没覆盖到那段时才走这条路径，补完才继续解码）。
 */
internal class PreviewByteWindow(
    private val origin: ByteSource,
    private val windowBytes: Long = PREVIEW_READ_WINDOW_BYTES,
    chunkBytes: Int = PREVIEW_READ_CHUNK_BYTES,
    readAheadSource: ByteSource? = null,
    private val readAhead: PreviewReadAhead = previewReadAhead,
    private val readAheadRank: Long = PREVIEW_PREFETCH_RANK,
) : ByteSource {

    /** 块粒度：不越过底层协商上限，64 KiB 服务器上一块正好一次读取。 */
    private val chunkBytes: Int = origin.maxReadBytes.takeIf { it in 1 until chunkBytes } ?: chunkBytes

    /** 预读用的字节源；没给独立句柄时与解码共用一条。它同时是预读派发的互斥键。 */
    private val aheadSource: ByteSource = readAheadSource ?: origin

    private val lock = Object()

    /** 已拉进本地的块，按起点索引，按最近使用淘汰。 */
    private val chunks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {}

    /** 常驻块的字节合计。 */
    private var residentBytes = 0L

    /** 正在向底层读的块起点；同一块只读一次。 */
    private val pending = HashSet<Long>()

    /** 读失败的块；重复读取按同一失败返回。 */
    private val failures = HashMap<Long, IOException>()

    /** 已排队但尚未开始的预读块。 */
    private val queued = LinkedHashSet<Long>()

    /** 最近一次触发向后读的块起点，块内重复读取不重复挂预读。 */
    private var lastFollowChunk = -1L

    private var closed = false

    override val size: Long get() = origin.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (position < 0 || position >= size) return 0
        val start = chunkStart(position)
        val bytes = chunkAt(start, position)
        val from = (position - start).toInt()
        if (from >= bytes.size) return 0
        val count = minOf(length, bytes.size - from)
        System.arraycopy(bytes, from, buffer, offset, count)
        followReadAhead(start)
        return count
    }

    /**
     * 元数据阶段定点读：只取文件头 64 KiB 与文件末尾 1 MiB，不铺满整窗预读。
     * 头尾区间相接的小文件一次读完，避免重复读同一段。
     */
    fun startMetadataReadAhead() {
        val total = size
        if (total <= 0) return
        val headEnd = minOf(total, PREVIEW_METADATA_HEAD_BYTES.toLong())
        // 尾部按块对齐后可能与头部落在同一块：这时一次读完，避免块头剩下的那段被跳过。
        val tailStart = chunkStart(maxOf(0L, total - PREVIEW_METADATA_TAIL_BYTES))
        if (tailStart <= headEnd) {
            queueMetadata(0, total)
            return
        }
        queueMetadata(0, headEnd)
        queueMetadata(tailStart, total)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            queued.clear()
            pending.clear()
            chunks.clear()
            residentBytes = 0
            lock.notifyAll()
        }
        runCatching { origin.close() }
        if (aheadSource !== origin) runCatching { aheadSource.close() }
    }

    /**
     * 作废窗口：关闭两条句柄，并让底层丢弃已失效的读取会话。
     * 超时、取消或打开失败后用它收尾，下一次读取不会复用该会话；可重复调用。
     */
    override fun discard() {
        close()
        origin.discard()
        if (aheadSource !== origin) aheadSource.discard()
    }

    /**
     * 取块内容；缺失时由当前线程补齐，补齐期间其它线程等锁上的通知。
     * 定点读只填了块头一段；解码要块内更后面的字节时整块重读，
     * 256 KiB 的块从块头再读一遍，包含已经读到的前 64 KiB。
     */
    private fun chunkAt(start: Long, position: Long): ByteArray {
        synchronized(lock) {
            while (true) {
                if (closed) throw IOException("预览读取已取消")
                val cached = chunks[start]
                if (cached != null && position - start < cached.size) return cached
                if (cached != null) {
                    chunks.remove(start)
                    residentBytes -= cached.size
                }
                failures[start]?.let { throw it }
                // 已排队但还没开始的块由解码接手：预读任务随后因不在队列里直接退出，
                // 同一段字节不会向底层读两遍。正在读的块照旧等它回写。
                queued.remove(start)
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
                    onSuccess = { bytes -> putChunkLocked(start, bytes) },
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

    /** 解码命中后向后扩 2 块；已在本地或读完的位置不排队。 */
    private fun followReadAhead(start: Long) {
        val tasks = ArrayList<Long>()
        synchronized(lock) {
            if (closed || lastFollowChunk == start) return
            lastFollowChunk = start
            var next = start + chunkBytes
            repeat(FOLLOW_READ_AHEAD_CHUNKS) {
                if (next < size && chunks[next] == null && next !in pending && next !in queued) {
                    queued += next
                    tasks += next
                }
                next += chunkBytes
            }
        }
        tasks.forEach { chunk ->
            val to = minOf(size, chunk + chunkBytes)
            readAhead.submit(readAheadRank, aheadSource) { readAheadChunk(chunk, to) }
        }
    }

    /**
     * 按块排队定点读：头 64 KiB 只填块头一段，同一块的后半段等解码用到时整块重读；其余块补齐整块。
     */
    private fun queueMetadata(from: Long, to: Long) {
        val tasks = ArrayList<Pair<Long, Long>>()
        synchronized(lock) {
            if (closed) return
            var start = chunkStart(from)
            while (start < to) {
                if (chunks[start] == null && start !in pending && start !in queued) {
                    queued += start
                    tasks += start to minOf(to, start + chunkBytes)
                }
                start += chunkBytes
            }
        }
        tasks.forEach { (chunk, end) ->
            readAhead.submit(readAheadRank, aheadSource) { readAheadChunk(chunk, end) }
        }
    }

    /** 后台读一块；已排队取消、已关闭、块已就绪或正在读取时直接退出。 */
    private fun readAheadChunk(chunk: Long, to: Long) {
        synchronized(lock) {
            if (!queued.remove(chunk)) return
            // 唤醒等着这块的读取：他们要么等到回写，要么接手自己补。
            lock.notifyAll()
            if (closed || chunk in pending) return
            if (chunks[chunk]?.let { it.size >= to - chunk } == true) return
            pending += chunk
        }
        val result = runCatching {
            val bytes = ByteArray((to - chunk).toInt())
            var read = 0
            while (read < bytes.size) {
                val count = aheadSource.readAt(chunk + read, bytes, read, bytes.size - read)
                if (count <= 0) break
                read += count
            }
            if (read == bytes.size) bytes else bytes.copyOf(read)
        }
        synchronized(lock) {
            pending -= chunk
            // 关闭后不回写；既有的失败记录由下一次读取暴露。
            if (!closed) {
                result.onSuccess { bytes -> if (bytes.isNotEmpty()) putChunkLocked(chunk, bytes) }
            }
            lock.notifyAll()
        }
    }

    /** 写回一块并按最近使用淘汰窗口外的块。 */
    private fun putChunkLocked(start: Long, bytes: ByteArray) {
        chunks.remove(start)?.let { residentBytes -= it.size }
        chunks[start] = bytes
        residentBytes += bytes.size
        val iterator = chunks.entries.iterator()
        while (residentBytes > windowBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == start) continue
            iterator.remove()
            residentBytes -= entry.value.size
        }
    }

    private fun chunkStart(position: Long): Long = position - position % chunkBytes

    private fun Throwable.asPreviewReadFailure(): IOException =
        this as? IOException ?: IOException("预览字节读取失败", this)
}
