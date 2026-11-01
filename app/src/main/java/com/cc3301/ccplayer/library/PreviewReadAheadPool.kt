package com.cc3301.ccplayer.library

/** 预取条目的预读 rank：排在全部可见项之后。 */
const val PREVIEW_PREFETCH_RANK = Long.MAX_VALUE - 1

/** 时长补读的 rank：排在最后。 */
const val PREVIEW_DURATION_RANK = Long.MAX_VALUE

/** 预读线程数：一条。同一 SMB 会话不支持并发读，超出一条会丢会话。 */
private const val PREVIEW_READ_AHEAD_THREADS = 1

private const val PREVIEW_READ_AHEAD_THREAD_NAME = "preview-byte-read-ahead"

/** 关闭时等线程退出的上限；阻塞中的传输读取由各自的超时收尾。 */
private const val PREVIEW_READ_AHEAD_CLOSE_MILLIS = 5_000L

/**
 * 预读派发接缝：按 rank 与所属字节源决定哪一块先读。
 */
fun interface PreviewReadAhead {

    /**
     * 提交一块预读。[rank] 越小越先派发；[key] 相同的任务不并发执行。
     * 池关闭后提交的任务直接丢弃。
     */
    fun submit(rank: Long, key: Any, task: Runnable)
}

/** 进程内唯一的预读池：整个进程只有一条线程，切源不重建。 */
val previewReadAhead: PreviewReadAheadPool by lazy { PreviewReadAheadPool() }

/**
 * 预读线程池：固定一条线程，按 rank 派发，同一时刻只有一个读。
 *
 * 派发取队列里 rank 最小者，rank 相同按提交顺序；已在跑的读取不抢占。
 * [key] 相同的任务不并发，一条字节源上的两次读不会交叉，保持单读假设。
 * 线程在第一次提交时才建，[close] 丢弃未开始的任务、中断并等待线程退出。
 */
class PreviewReadAheadPool(
    private val threadCount: Int = PREVIEW_READ_AHEAD_THREADS,
    private val threadName: String = PREVIEW_READ_AHEAD_THREAD_NAME,
) : PreviewReadAhead {

    private val lock = Object()

    /** 已提交未开始的任务，按提交顺序排列。 */
    private val tasks = ArrayList<Task>()

    /** 正在读的字节源；同一 key 不并发。 */
    private val runningKeys = HashSet<Any>()

    private val workers = ArrayList<Thread>()

    private var closed = false

    override fun submit(rank: Long, key: Any, task: Runnable) {
        synchronized(lock) {
            if (closed) return
            tasks += Task(rank, key, task)
            while (workers.size < threadCount) startWorkerLocked()
            lock.notifyAll()
        }
    }

    /** 关闭：未开始的任务不再执行，线程中断后退出。 */
    fun close() {
        val stopping = synchronized(lock) {
            if (closed) return
            closed = true
            tasks.clear()
            lock.notifyAll()
            workers.toList()
        }
        stopping.forEach { worker ->
            worker.interrupt()
            try {
                worker.join(PREVIEW_READ_AHEAD_CLOSE_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun startWorkerLocked() {
        val worker = Thread({ work() }, "$threadName-${workers.size + 1}").apply { isDaemon = true }
        workers += worker
        worker.start()
    }

    private fun work() {
        while (true) {
            val task = take() ?: return
            try {
                task.command.run()
            } catch (_: Throwable) {
                // 预读失败由字节窗口按块记录；线程必须活着接下一块。
            } finally {
                synchronized(lock) {
                    runningKeys -= task.key
                    lock.notifyAll()
                }
            }
        }
    }

    /** 取下一个可跑的任务；没有时等通知，关闭后返回 null。 */
    private fun take(): Task? {
        synchronized(lock) {
            while (!closed) {
                val index = nextIndexLocked()
                if (index >= 0) {
                    val task = tasks.removeAt(index)
                    runningKeys += task.key
                    return task
                }
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    // 关闭时的中断靠 closed 退出；其余情况继续等。
                }
            }
            return null
        }
    }

    /** rank 最小、且其字节源当前没在读的任务；rank 相同取先提交的。 */
    private fun nextIndexLocked(): Int {
        var best = -1
        for (index in tasks.indices) {
            val task = tasks[index]
            if (task.key in runningKeys) continue
            if (best < 0 || task.rank < tasks[best].rank) best = index
        }
        return best
    }

    private class Task(val rank: Long, val key: Any, val command: Runnable)
}
