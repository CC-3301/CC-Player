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
 * 预览跳转的 [retarget] 就地改写仍在执行的那一跳：目标换成新值、待执行丢弃、按同一身份重新下发，
 * 队列里因此只剩当前最后目标，被替换的旧目标不会再出帧。
 * 新加载或停止时在途与待执行一并作废。
 * 字段只在同一把锁内读写。
 */
internal class MpvSeekQueue(
    private val post: (seekId: Long) -> Unit,
    private val onExecuted: (seekId: Long, targetMillis: Long) -> Unit,
    private val armTimeout: (onTimeout: () -> Unit) -> Unit,
    private val cancelTimeout: () -> Unit,
) {

    /** 一跳 seek：身份、mpv 命令序号与目标毫秒；目标可被预览改写，[issued] 表示 mpv 命令已经下发。 */
    private class Entry(val seekId: Long, val serial: Int, var targetMillis: Long) {
        var issued = false

        /** 已下发的那个目标：改写前投递的任务不能把同一个目标再发一次。 */
        var issuedTargetMillis: Long? = null
    }

    /** 改写后仍是同一个在途身份：落定按这个 seekId 认这一跳。 */
    data class Active(val seekId: Long, val serial: Int)

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
        post(toPost.seekId)
    }

    /**
     * 预览目标改写仍在执行的那一跳：目标就地更新、待执行被丢弃，随后按同一身份重新下发命令。
     * 队列里只剩当前最后目标，被改写掉的旧目标不会再出帧；命令序号沿用原来的，ready 仍按它认这一跳。
     * 没有在途时返回 null，由调用方按新的一跳入队。
     */
    fun retarget(targetMillis: Long): Active? {
        val retargeted = synchronized(lock) {
            val entry = inFlight ?: return null
            entry.targetMillis = targetMillis
            pending = null
            entry
        }
        startTimeout()
        post(retargeted.seekId)
        return Active(retargeted.seekId, retargeted.serial)
    }

    /**
     * 命令线程上真正下发这一跳的 mpv 命令：持锁校验它仍是当前在途，校验与下发之间不换跳。
     * 持锁是为与 release/destroy 互斥；[issue] 里只登记归属与下发命令，不读属性、不写诊断。
     * 命令抛异常时这一跳停在「在途但未下发」，回执与超时都不会结算它；这里就地结清，
     * 否则后续 seek 只能堆在待执行槽里，落定没有出口。
     * [firstIssue] 为假表示这是预览改写目标后的再次下发：命令照发，序号与诊断归属不重复登记。
     * 返回这一跳的命令序号；已不是当前在途时返回 null，命令不下发。
     */
    fun issueIfCurrent(
        seekId: Long,
        issue: (serial: Int, targetMillis: Long, firstIssue: Boolean) -> Unit,
    ): Int? {
        var failure: Throwable? = null
        val serial = synchronized(lock) {
            val entry = inFlight ?: return null
            if (entry.seekId != seekId) return null
            // 目标已经按这个值下发过：改写前投递的重复任务不再下发。
            if (entry.issued && entry.issuedTargetMillis == entry.targetMillis) return null
            val firstIssue = !entry.issued
            try {
                issue(entry.serial, entry.targetMillis, firstIssue)
                entry.issued = true
                entry.issuedTargetMillis = entry.targetMillis
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
            // 撤计时器与清在途在同一临界区：不会撤掉随后新起的一跳自己的超时兜底。
            cancelTimeout()
        }
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
            // 撤计时器与换跳在同一临界区：另一个线程刚起的下一跳不会在这里丢掉自己的超时兜底。
            timeoutGeneration++
            cancelTimeout()
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
            // 与回执路径一致：撤计时器与换跳在同一临界区。
            timeoutGeneration++
            cancelTimeout()
            Completion(entry, next)
        }
        finish(completion)
    }

    /** 结算收尾：回报这一跳、补发待执行那一跳；计时器已在结算的临界区撤掉。 */
    private fun finish(completion: Completion) {
        onExecuted(completion.executed.seekId, completion.executed.targetMillis)
        completion.next?.let { next ->
            startTimeout()
            post(next.seekId)
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
