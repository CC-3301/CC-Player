package com.cc3301.ccplayer.playback

/**
 * 引擎的加载代际与结束事件判定。
 *
 * mpv 的事件不带文件标识：`loadfile replace` 会给被替换的旧文件发 END_FILE；
 * 新加载被中止时（例如切集中返回取消）也会给在途文件发 END_FILE；
 * 加载失败则只有 END_FILE、没有 FILE_LOADED。这里用“待吞的结束事件计数”
 * 把旧代际的结束事件先消耗掉，再用当前代际是否已 FILE_LOADED 判断自然结束或失败。
 *
 * 控制层线程会调用 start/stop，mpv 事件线程会调用其余方法，所有方法都在同一把锁内。
 */
internal class MpvFileEvents {

    private enum class State { Idle, Loading, Loaded }

    private var state = State.Idle

    /**
     * 已被新 loadfile 命令取代、但还没收到 END_FILE 的旧文件数量。
     * 每个新 start 都会给上一个在途/已加载文件记一次。
     */
    private var pendingEnds = 0

    /** 当前文件是否已到末尾（只接受当前代际的属性事件）。 */
    private var eof = false

    /** 显式 stop 之后、下一次 start 之前，所有 END_FILE 都不算播放结果。 */
    private var stopping = false

    /**
     * 看门狗丢弃死代际后置位：之后迟到的 END_FILE 不改变状态，
     * 直到下一次 FILE_LOADED 成功，避免死代际的结束把恢复加载判成失败。
     */
    private var discardBarrier = false

    @get:Synchronized
    val isLoaded: Boolean get() = state == State.Loaded

    /** 是否有被取代文件的 END_FILE 还没消化；此时先到的 FILE_LOADED 一定属于更早代际。 */
    @get:Synchronized
    val hasPendingEnds: Boolean get() = pendingEnds > 0

    /** 下发新的 loadfile：上一个在途或已加载文件之后会来一次 END_FILE。 */
    @Synchronized
    fun onStart() {
        stopping = false
        if (state != State.Idle) pendingEnds++
        state = State.Loading
        eof = false
    }

    /**
     * 主动停止：在途或已加载文件会因停止再产生一次 END_FILE。
     * 已有的待吞计数必须保留，否则停止前被替换的在途加载会漏吞，后续结束事件被误判失败。
     */
    @Synchronized
    fun onStop() {
        if (state != State.Idle) pendingEnds++
        state = State.Idle
        eof = false
        stopping = true
    }

    /**
     * 收到 FILE_LOADED；[isTarget] 为假表示迟到的是旧代际文件。
     * 停止之后迟到的 FILE_LOADED 同样拒绝。
     */
    @Synchronized
    fun onFileLoaded(isTarget: Boolean): Boolean {
        if (stopping || !isTarget) return false
        state = State.Loaded
        eof = false
        discardBarrier = false
        return true
    }

    /** 记录 eof-reached 属性；旧代际的迟到事件不采纳。 */
    @Synchronized
    fun onEofChanged(value: Boolean) {
        if (state == State.Loaded) eof = value
    }

    /** 结束事件是否算自然播完；位置到达结尾时兜底 eof 属性未上报。 */
    @Synchronized
    fun onEndFile(atEndByPosition: Boolean): End {
        // 丢弃死代际后，迟到的结束事件不能作用到恢复加载。
        if (discardBarrier) return End.Replaced
        if (pendingEnds > 0) {
            pendingEnds--
            return End.Replaced
        }
        if (stopping) {
            state = State.Idle
            eof = false
            return End.Replaced
        }
        val natural = state == State.Loaded && (eof || atEndByPosition)
        state = State.Idle
        eof = false
        return if (natural) End.Ended else End.Failed
    }

    /**
     * 看门狗丢弃死代际：结束计数一并归零，不指望死代际补发 END_FILE；
     * 之后若真的来了结束事件，用 [discardBarrier] 吞掉。
     */
    @Synchronized
    fun onDiscardInFlight() {
        state = State.Idle
        pendingEnds = 0
        eof = false
        stopping = false
        discardBarrier = true
    }

    /** 释放时清空全部代际状态。 */
    @Synchronized
    fun reset() {
        state = State.Idle
        pendingEnds = 0
        eof = false
        stopping = false
        discardBarrier = false
    }

    enum class End { Replaced, Ended, Failed }
}

/** 一次 loadfile 的目标与代际；URL 与序号总是成对使用。 */
internal data class LoadSpec(val url: String, val token: Int)

/**
 * loadfile 与 FILE_LOADED 的代际关联：每次 start 入队在途加载，FILE_LOADED 取最早的一条，
 * 因此同 URL 重载时迟到的旧 FILE_LOADED 仍带旧代际。
 *
 * 被新加载取代或停止的在途文件不会再有 FILE_LOADED，只会先来一次 END_FILE；
 * 结束队列按命令顺序记录每个将要结束的文件，用它把对应在途加载移出。
 * 控制层线程调用 start/stop，mpv 事件线程调用其余方法，所有方法同步。
 */
internal class MpvLoadQueue {

    private val pendingLoads = ArrayDeque<LoadSpec>()
    private val endingLoads = ArrayDeque<LoadSpec>()
    private var current: LoadSpec? = null
    private var nextToken = 0

    /** 已确认 FILE_LOADED 的加载；后续 ready 事件沿用这份快照。 */
    @Volatile
    var loaded: LoadSpec? = null
        private set

    /** 下发新的 loadfile；上一个在途或已加载文件之后会来一次 END_FILE。 */
    @Synchronized
    fun onStart(url: String): LoadSpec {
        current?.let { endingLoads.addLast(it) }
        val spec = LoadSpec(url, ++nextToken)
        current = spec
        loaded = null
        pendingLoads.addLast(spec)
        return spec
    }

    /**
     * 校验后取出队首 FILE_LOADED。[path] 是 mpv 当前 path；与队首不一致时保留队列：
     * 停止后紧接新加载时，迟到的旧 FILE_LOADED 不能占掉新加载的位置。
     */
    @Synchronized
    fun takeFileLoaded(path: String?): LoadSpec? {
        val head = pendingLoads.firstOrNull() ?: return null
        if (!isTargetPath(head.url, path)) return null
        pendingLoads.removeFirst()
        loaded = head
        return head
    }

    /** 已加载且仍是当前代际；旧代际的 FILE_LOADED 不满足，ready 不能上报。 */
    @Synchronized
    fun currentLoaded(): LoadSpec? = loaded?.takeIf { it === current }

    /**
     * 收到 END_FILE：取出产生这次结束的加载，并清掉它在途记录。
     * [isReplaced] 为假表示这是当前代际自己的结束；为真时只有确实登记过的旧文件才能被移出，
     * 否则丢弃死代际后迟到的结束事件会误伤当前加载。
     */
    @Synchronized
    fun onEndFile(isReplaced: Boolean): LoadSpec? {
        val ended = if (isReplaced) {
            endingLoads.removeFirstOrNull() ?: return null
        } else {
            current
        }
        pendingLoads.remove(ended)
        if (current === ended) current = null
        if (loaded === ended) loaded = null
        return ended
    }

    /** 主动停止：在途加载不会再有 FILE_LOADED，正在播放的文件会来一次 END_FILE。 */
    @Synchronized
    fun onStop() {
        current?.let { endingLoads.addLast(it) }
        current = null
        loaded = null
        pendingLoads.clear()
    }

    /**
     * 丢弃不会再产生事件的死代际（看门狗恢复前）。
     * 保留结束队列为空：迟到的 END_FILE 由旧代际吞掉，不能作用到恢复加载。
     */
    @Synchronized
    fun discardInFlight() {
        current = null
        loaded = null
        pendingLoads.clear()
        endingLoads.clear()
    }

    @Synchronized
    fun reset() {
        current = null
        loaded = null
        pendingLoads.clear()
        endingLoads.clear()
    }
}

/** 一次可上报的 ready 信号：产生它的加载与 seek 序号。 */
internal data class ReadySignal(val spec: LoadSpec, val serial: Int)

/**
 * seek 与 ready 的序号：控制层只认续播 seek 之后的信号。
 *
 * seek 命令下发时先登记序号，但要等 MPV_EVENT_SEEK 到达才把它设为“当前可接受序号”。
 * 这样即使旧文件的 restart/reconfig 在 seek 命令之后才被处理，它仍带旧序号，不会被误认。
 * 同一序号内，需抓帧确认与无需确认的 ready 各只上报一次。
 * 控制层线程与 mpv 事件线程都会调用，方法同步。
 */
internal class MpvReadySignals {

    private var issued = 0
    private val pendingSeeks = ArrayDeque<Int>()
    private var accepted = 0
    private var lastReconfigSerial = -1
    private var lastRestartSerial = -1

    /** 新文件开始加载：丢弃上一轮未等到 SEEK 事件的序号，接受序号从头开始。 */
    @Synchronized
    fun onStart() {
        issued = 0
        pendingSeeks.clear()
        accepted = 0
        lastReconfigSerial = -1
        lastRestartSerial = -1
    }

    /** 下发 seek，返回该 seek 的序号；对应 MPV_EVENT_SEEK 到达前，ready 仍用旧序号。 */
    @Synchronized
    fun onSeekIssued(): Int {
        issued++
        pendingSeeks.addLast(issued)
        return issued
    }

    /** MPV_EVENT_SEEK 到达：后续 ready 改用这个 seek 的序号。 */
    @Synchronized
    fun onSeekEvent() {
        pendingSeeks.removeFirstOrNull()?.let { accepted = it }
    }

    /** 本次 ready 的序号；同一序号内同类信号重复时返回 null。 */
    @Synchronized
    fun acceptReady(needsFrameConfirmation: Boolean): Int? {
        if (needsFrameConfirmation) {
            if (lastReconfigSerial == accepted) return null
            lastReconfigSerial = accepted
        } else {
            if (lastRestartSerial == accepted) return null
            lastRestartSerial = accepted
        }
        return accepted
    }

    @Synchronized
    fun reset() {
        issued = 0
        pendingSeeks.clear()
        accepted = 0
        lastReconfigSerial = -1
        lastRestartSerial = -1
    }
}

/**
 * 把文件结束判定、加载代际与 ready 序号放在同一临界区：
 * start 重置序号与 ready 校验占槽不会交错，旧代际 ready 无法占新代际的槽。
 * 控制层线程调用 start/stop，mpv 事件线程调用其余方法。
 */
internal class MpvLoadCoordinator(
    private val loads: MpvLoadQueue = MpvLoadQueue(),
    private val fileEvents: MpvFileEvents = MpvFileEvents(),
    private val readySignals: MpvReadySignals = MpvReadySignals(),
) {

    @get:Synchronized
    val isFileLoaded: Boolean get() = fileEvents.isLoaded

    /** 新的 loadfile：重置 ready 序号、登记在途加载。 */
    @Synchronized
    fun start(url: String): LoadSpec {
        fileEvents.onStart()
        readySignals.onStart()
        return loads.onStart(url)
    }

    @Synchronized
    fun stop() {
        loads.onStop()
        fileEvents.onStop()
    }

    /** 看门狗恢复前丢弃死代际，并归零结束计数，避免吞掉恢复文件的自然结束。 */
    @Synchronized
    fun discardInFlight() {
        loads.discardInFlight()
        fileEvents.onDiscardInFlight()
    }

    /**
     * FILE_LOADED 归属：先看是否还有旧文件的 END_FILE 未消化，再按 mpv path 校验队首。
     * stop 后同 URL 重开时，迟到的旧 FILE_LOADED 会因待吞结束而先被拒绝。
     */
    @Synchronized
    fun onFileLoaded(path: String?): LoadSpec? {
        if (fileEvents.hasPendingEnds) return null
        val spec = loads.takeFileLoaded(path) ?: return null
        if (!fileEvents.onFileLoaded(isTarget = true)) return null
        return spec
    }

    @Synchronized
    fun onEndFile(atEndByPosition: Boolean): MpvFileEvents.End {
        val end = fileEvents.onEndFile(atEndByPosition)
        loads.onEndFile(end == MpvFileEvents.End.Replaced)
        return end
    }

    @Synchronized
    fun onSeekIssued(): Int = readySignals.onSeekIssued()

    @Synchronized
    fun onSeekEvent() {
        readySignals.onSeekEvent()
    }

    @Synchronized
    fun onEofChanged(value: Boolean) {
        fileEvents.onEofChanged(value)
    }

    /** 代际校验与占槽在同一临界区；旧代际 ready 不占当前代际的槽。 */
    @Synchronized
    fun acceptReady(needsFrameConfirmation: Boolean): ReadySignal? {
        val spec = loads.currentLoaded() ?: return null
        val serial = readySignals.acceptReady(needsFrameConfirmation) ?: return null
        return ReadySignal(spec, serial)
    }

    @Synchronized
    fun reset() {
        loads.reset()
        fileEvents.reset()
        readySignals.reset()
    }
}
