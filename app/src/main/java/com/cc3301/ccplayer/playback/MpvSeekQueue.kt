package com.cc3301.ccplayer.playback

/**
 * 引擎侧的 seek 队列：恒为「1 执行 + 1 待执行」，每一跳带一个永不复用的 seekId 与本代命令序号。
 *
 * 身份由引擎分配，加载与停止都不重置它；命令序号在入队时预留，ready 带回同一个序号。
 * 下发分两步：[seek] 只把这一跳交给 [post]，命令线程再经 [issueIfCurrent] 校验「仍是当前在途」后才下发 mpv 命令；
 * 持队列锁下发是为与 release/destroy 互斥，属性读取与诊断写盘都不在队列锁内做。
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

    /** 结算结论：结算了哪一跳（没结算为 null），以及超时路径是否要为「命令还没下发」重新计时。 */
    private class Outcome(val settledSeekId: Long?, val rearm: Boolean)

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
     * 命令抛异常时这一跳停在「在途但未下发」，回执与超时都不会结算它；这里就地结清，
     * 否则后续 seek 只能堆在待执行槽里，落定没有出口。
     * 返回这一跳的命令序号；已不是当前在途时返回 null，命令不下发。
     */
    fun issueIfCurrent(seekId: Long, issue: (serial: Int) -> Unit): Int? {
        var failure: Throwable? = null
        val serial = synchronized(lock) {
            val entry = inFlight ?: return null
            if (entry.seekId != seekId) return null
            try {
                issue(entry.serial)
                entry.issued = true
                entry.serial
            } catch (error: Throwable) {
                failure = error
                null
            }
        }
        val error = failure ?: return serial
        // 命令抛异常时这一跳不可能再有回执：按执行完结清并补发待执行，异常继续交给调用方。
        fail(seekId)
        throw error
    }

    /** 在途这一跳已执行完：[serial] 是 ready 携带的序号，只结算已下发且序号相等的那一跳。 */
    fun onReceipt(serial: Int): Long? = complete(receiptSerial = serial).settledSeekId

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

    /**
     * 结算在途这一跳并补发待执行；代际、序号校验、取在途与「超时是否重新计时」在同一临界区。
     * 重新计时由返回值表达，避免判定之后再到锁外补一次判断时 issued 已变真、这一跳就没有计时器。
     */
    private fun complete(receiptSerial: Int? = null, generation: Long? = null): Outcome {
        val completion = synchronized(lock) {
            if (generation != null && generation != timeoutGeneration) return Outcome(null, rearm = false)
            val executed = inFlight ?: return Outcome(null, rearm = false)
            // 命令还没下发：这一跳没有已下发的序号可对，任何回执都不结算它；
            // 只有本次到点的计时器仍有效时，才在锁内直接决定重新计时等它下发。
            if (!executed.issued) return Outcome(null, rearm = generation != null)
            if (receiptSerial != null && executed.serial != receiptSerial) return Outcome(null, rearm = false)
            val next = pending
            pending = null
            inFlight = next
            Completion(executed, next)
        }
        finish(completion)
        return Outcome(completion.executed.seekId, rearm = false)
    }

    /** 命令失败：这一跳不可能再有回执，按执行完结清，队列不会停在「在途但未下发」。 */
    private fun fail(seekId: Long) {
        val completion = synchronized(lock) {
            val entry = inFlight ?: return
            if (entry.seekId != seekId || entry.issued) return
            val next = pending
            pending = null
            inFlight = next
            Completion(entry, next)
        }
        finish(completion)
    }

    /** 结算收尾：撤掉计时器、回报这一跳、补发待执行那一跳。 */
    private fun finish(completion: Completion) {
        cancelTimeout()
        onExecuted(completion.executed.seekId, completion.executed.targetMillis)
        completion.next?.let { next ->
            startTimeout()
            post(next.seekId, next.targetMillis)
        }
    }

    private fun startTimeout() {
        val generation = synchronized(lock) { ++timeoutGeneration }
        armTimeout { onTimeoutIfCurrent(generation) }
    }

    /** 到点：代际校验、结算与是否重新计时都在同一次临界区；命令还没下发时重新计时等它下发。 */
    private fun onTimeoutIfCurrent(generation: Long) {
        if (complete(generation = generation).rearm) startTimeout()
    }
}
