package com.cc3301.ccplayer.playback

/**
 * 引擎侧的 seek 队列：恒为「1 执行 + 1 待执行」。
 *
 * 在途这一跳执行完（回执或超时）后立即补发被覆盖的最后一个目标，并回报已执行的目标毫秒；
 * 超时也按执行完处理，回执丢失不会让队列停在第一帧。新加载或停止时在途与待执行一并作废。
 * 字段只在同一把锁内读写，[issue] 与 [onExecuted] 都在锁外执行，不会与 mpv 事件线程互相等待。
 */
internal class MpvSeekQueue(
    private val issue: (targetMillis: Long) -> Unit,
    private val onExecuted: (targetMillis: Long) -> Unit,
    private val armTimeout: (onTimeout: () -> Unit) -> Unit,
    private val cancelTimeout: () -> Unit,
) {

    /** 一次「执行完」的结果：刚执行的目标与接着下发的待执行目标。 */
    private class Completion(val executedMillis: Long, val nextMillis: Long?)

    private val lock = Any()
    private var inFlightMillis: Long? = null
    private var pendingMillis: Long? = null

    /** 计时器代数：重置或换下一跳后，旧计时器到点不再算数。 */
    private var timeoutGeneration = 0L

    /** 下发一跳 seek；在途期间只覆盖待执行目标，命令队列不会增长。 */
    fun seek(positionMillis: Long) {
        synchronized(lock) {
            if (inFlightMillis != null) {
                pendingMillis = positionMillis
                return
            }
            inFlightMillis = positionMillis
        }
        startTimeout()
        issue(positionMillis)
    }

    /** 在途这一跳已执行完：回报目标毫秒并补发待执行目标。 */
    fun onReceipt() = complete()

    /** 在途这一跳超时：同样按执行完处理，照常回报。 */
    fun onTimeout() = complete()

    /** 新加载或停止：在途与待执行一并作废，不再回报也不再补发。 */
    fun reset() {
        synchronized(lock) {
            inFlightMillis = null
            pendingMillis = null
            timeoutGeneration++
        }
        cancelTimeout()
    }

    private fun complete() {
        val completion = synchronized(lock) {
            val executed = inFlightMillis ?: return
            val next = pendingMillis
            pendingMillis = null
            inFlightMillis = next
            Completion(executed, next)
        }
        cancelTimeout()
        onExecuted(completion.executedMillis)
        completion.nextMillis?.let { next ->
            startTimeout()
            issue(next)
        }
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
