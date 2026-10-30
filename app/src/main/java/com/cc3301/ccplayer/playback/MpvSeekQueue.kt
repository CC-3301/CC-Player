package com.cc3301.ccplayer.playback

/**
 * 引擎侧的 seek 队列：恒为「1 执行 + 1 待执行」，每一跳带一个永不复用的 seekId 与本代命令序号。
 *
 * 身份由引擎分配，加载与停止都不重置它；命令序号在入队时预留，ready 带回同一个序号。
 * 下发分两步：[seek] 只把这一跳交给 [post]，命令线程再经 [issueIfCurrent] 校验「仍是当前在途」后才下发 mpv 命令；
 * 持队列锁下发是为与 release/destroy 互斥，属性读取与诊断写盘都不在锁内做。
 * 切集或停止插在入队与下发之间时，旧目标因此不会打到新文件上。
 * 结算只认「已下发且序号相等」的那一跳：[onReceipt] 拿 ready 携带的序号比对，命令还没下发或序号不符时什么都不做；
 * 超时结算并补发下一跳之后，前一跳迟到的回执因此不会结算新的一跳。
 * 在途这一跳执行完（回执或超时）后立即补发被覆盖的最后一个目标，并回报它自己的 seekId 与目标毫秒。
 * 新加载或停止时在途与待执行一并作废。
 * 字段只在同一把锁内读写。
 */
internal class MpvSeekQueue(
    private val post: (seekId: Long, targetMillis: Long) -> Unit,
    private val onExecuted: (seekId: Long, targetMillis: Long) -> Unit,
    private val armTimeout: (onTimeout: () -> Unit) -> Unit,
    private val cancelTimeout: () -> Unit,
) {

    /** 一跳 seek：身份、mpv 命令序号与目标毫秒；[issued] 表示 mpv 命令已经下发。 */
    private class Entry(val seekId: Long, val serial: Int, val targetMillis: Long) {
        var issued = false
    }

    /** 一次「执行完」：刚执行的一跳，以及接着要下发的待执行一跳。 */
    private class Completion(val executed: Entry, val next: Entry?)

    private val lock = Any()
    private var inFlight: Entry? = null
    private var pending: Entry? = null

    /** 计时器代数：重置或换下一跳后，旧计时器到点不再算数。 */
    private var timeoutGeneration = 0L

    /** 下发一跳 seek；在途期间只覆盖待执行目标，命令队列不会增长。 */
    fun seek(seekId: Long, serial: Int, targetMillis: Long) {
        val entry = Entry(seekId, serial, targetMillis)
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

    /**
     * 命令线程上真正下发这一跳的 mpv 命令：持锁校验它仍是当前在途，校验与下发之间不换跳。
     * 持锁是为与 release/destroy 互斥；[issue] 里只登记归属与下发命令，不读属性、不写诊断。
     * 返回这一跳的命令序号；已不是当前在途时返回 null，命令不下发。
     */
    fun issueIfCurrent(seekId: Long, issue: (serial: Int) -> Unit): Int? {
        synchronized(lock) {
            val entry = inFlight ?: return null
            if (entry.seekId != seekId) return null
            issue(entry.serial)
            entry.issued = true
            return entry.serial
        }
    }

    /** 在途这一跳已执行完：[serial] 是 ready 携带的序号，只结算已下发且序号相等的那一跳。 */
    fun onReceipt(serial: Int): Long? = complete(receiptSerial = serial)

    /** 在途这一跳超时：没有回执可对，按执行完处理，回执丢失不会让队列停在那一跳。 */
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

    /** 结算在途这一跳并补发待执行；代际、序号校验与取在途在同一临界区。 */
    private fun complete(receiptSerial: Int? = null, generation: Long? = null): Long? {
        val completion = synchronized(lock) {
            if (generation != null && generation != timeoutGeneration) return null
            val executed = inFlight ?: return null
            // 命令还没下发：这一跳没有已下发的序号可对，任何回执都不结算它。
            if (!executed.issued) return null
            if (receiptSerial != null && executed.serial != receiptSerial) return null
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

    /** 到点：代际校验与结算在同一次临界区；命令还没下发时不结算，重新计时等它下发。 */
    private fun onTimeoutIfCurrent(generation: Long) {
        if (complete(generation = generation) != null) return
        val waitingForIssue = synchronized(lock) {
            generation == timeoutGeneration && inFlight?.issued == false
        }
        if (waitingForIssue) startTimeout()
    }
}
