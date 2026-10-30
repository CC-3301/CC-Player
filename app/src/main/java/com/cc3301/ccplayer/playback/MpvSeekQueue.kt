package com.cc3301.ccplayer.playback

/**
 * 引擎侧的 seek 队列：恒为「1 执行 + 1 待执行」，每一跳带一个永不复用的 seekId。
 *
 * 身份由引擎分配，加载与停止都不重置它，回执按 seekId 认这一跳，不按目标毫秒。
 * 下发分两步：[seek] 只把这一跳交给 [post]，命令线程再经 [issueIfCurrent] 校验「仍是当前在途」后才下发 mpv 命令；
 * 切集或停止插在入队与下发之间时，旧目标因此不会打到新文件上。
 * 在途这一跳执行完（回执或超时）后立即补发被覆盖的最后一个目标，并回报它自己的 seekId 与目标毫秒；
 * 超时同样按执行完处理，回执丢失不会让队列停在那一跳。新加载或停止时在途与待执行一并作废。
 * 字段只在同一把锁内读写。
 */
internal class MpvSeekQueue(
    private val post: (seekId: Long, targetMillis: Long) -> Unit,
    private val onExecuted: (seekId: Long, targetMillis: Long) -> Unit,
    private val armTimeout: (onTimeout: () -> Unit) -> Unit,
    private val cancelTimeout: () -> Unit,
) {

    /** 一跳 seek：身份与目标毫秒。 */
    private class Entry(val seekId: Long, val targetMillis: Long)

    /** 一次「执行完」：刚执行的一跳，以及接着要下发的待执行一跳。 */
    private class Completion(val executed: Entry, val next: Entry?)

    private val lock = Any()
    private var inFlight: Entry? = null
    private var pending: Entry? = null

    /** 计时器代数：重置或换下一跳后，旧计时器到点不再算数。 */
    private var timeoutGeneration = 0L

    /** 下发一跳 seek；在途期间只覆盖待执行目标，命令队列不会增长。 */
    fun seek(seekId: Long, targetMillis: Long) {
        val entry = Entry(seekId, targetMillis)
        val toPost = synchronized(lock) {
            if (inFlight != null) {
                pending = entry
                null
            } else {
                inFlight = entry
                entry
            }
        } ?: return
        startTimeout()
        post(toPost.seekId, toPost.targetMillis)
    }

    /** 命令线程上真正下发：这一跳已不是当前在途（切集、停止或已换下一跳）时丢弃。 */
    fun issueIfCurrent(seekId: Long, command: () -> Unit) {
        synchronized(lock) {
            if (inFlight?.seekId != seekId) return
            command()
        }
    }

    /** 在途这一跳已执行完：回报它自己的身份并补发待执行目标；返回刚执行那一跳的 seekId。 */
    fun onReceipt(): Long? = complete()

    /** 在途这一跳超时：同样按执行完处理，照常回报。 */
    fun onTimeout() {
        complete()
    }

    /** 新加载或停止：在途与待执行一并作废，不再回报也不再补发。 */
    fun reset() {
        synchronized(lock) {
            inFlight = null
            pending = null
            timeoutGeneration++
        }
        cancelTimeout()
    }

    private fun complete(): Long? {
        val completion = synchronized(lock) {
            val executed = inFlight ?: return null
            val next = pending
            pending = null
            inFlight = next
            Completion(executed, next)
        }
        cancelTimeout()
        onExecuted(completion.executed.seekId, completion.executed.targetMillis)
        completion.next?.let { next ->
            startTimeout()
            post(next.seekId, next.targetMillis)
        }
        return completion.executed.seekId
    }

    private fun startTimeout() {
        val generation = synchronized(lock) { ++timeoutGeneration }
        armTimeout { onTimeoutIfCurrent(generation) }
    }

    private fun onTimeoutIfCurrent(generation: Long) {
        synchronized(lock) {
            if (generation != timeoutGeneration) return
        }
        complete()
    }
}
