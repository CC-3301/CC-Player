package com.cc3301.ccplayer.playback

/**
 * 引擎的加载代际与结束事件判定。
 *
 * mpv 的事件不带文件标识：`loadfile replace` 会给被替换的旧文件发 END_FILE；
 * 新加载被中止时（例如切集中返回取消）也会给在途文件发 END_FILE；
 * 加载失败则只有 END_FILE、没有 FILE_LOADED。这里用“待吞的结束事件计数”
 * 把旧代际的结束事件先消耗掉，再用当前代际是否已 FILE_LOADED 判断是否失败。
 * 自然播完不使用 END_FILE，只由 `eof-reached` 属性单独上报。
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

    /** 本轮加载是否已上报过自然播完；eof-reached 重复变 true 时不再重复上报。 */
    private var ended = false

    /** 显式 stop 之后、下一次 start 之前，所有 END_FILE 都不算播放结果。 */
    private var stopping = false

    /**
     * 加载看护丢弃死代际后置位：之后迟到的 END_FILE 不改变状态，
     * 直到下一次 FILE_LOADED 成功，避免死代际的结束把恢复加载判成失败。
     */
    private var discardBarrier = false

    @get:Synchronized
    val isLoaded: Boolean get() = state == State.Loaded

    /** 是否有被取代文件的 END_FILE 还没消化。 */
    @get:Synchronized
    val hasPendingEnds: Boolean get() = pendingEnds > 0

    /** 下发新的 loadfile：上一个在途或已加载文件之后会来一次 END_FILE。 */
    @Synchronized
    fun onStart() {
        stopping = false
        if (state != State.Idle) pendingEnds++
        state = State.Loading
        ended = false
    }

    /**
     * 主动停止：在途或已加载文件会因停止再产生一次 END_FILE。
     * 已有的待吞计数必须保留，否则停止前被替换的在途加载会漏吞，后续结束事件被误判失败。
     */
    @Synchronized
    fun onStop() {
        if (state != State.Idle) pendingEnds++
        state = State.Idle
        ended = false
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
        ended = false
        discardBarrier = false
        return true
    }

    /**
     * 记录 eof-reached 属性；旧代际的迟到事件不采纳。
     * 返回 true 表示这次变化就是本轮的自然播完，应立刻触发自动下一集。
     */
    @Synchronized
    fun onEofChanged(value: Boolean): Boolean {
        if (state != State.Loaded) return false
        if (!value) return false
        if (ended) return false
        ended = true
        return true
    }

    /**
     * 真正离开 EOF：seek 发起后允许同一代际再次上报自然播完。
     * 单纯 eof=false 可能只是卸载前的属性回落，不能重置。
     */
    @Synchronized
    fun onSeekIssued() {
        if (state == State.Loaded) ended = false
    }

    /** END_FILE 只用来区分替换与失败；自然播完已由 [onEofChanged] 上报。 */
    @Synchronized
    fun onEndFile(): End {
        // 丢弃死代际后，迟到的结束事件不能作用到恢复加载。
        if (discardBarrier) return End.Replaced
        if (pendingEnds > 0) {
            pendingEnds--
            return End.Replaced
        }
        if (stopping) {
            state = State.Idle
            ended = false
            return End.Replaced
        }
        // 播完后 keep-open 仍停在末帧；后续 END_FILE 只是卸载，不是失败。
        val alreadyEnded = ended
        state = State.Idle
        ended = false
        return if (alreadyEnded) End.Ended else End.Failed
    }

    /**
     * 加载看护丢弃死代际：结束计数一并归零，不指望死代际补发 END_FILE；
     * 之后若真的来了结束事件，用 [discardBarrier] 吞掉。
     */
    @Synchronized
    fun onDiscardInFlight() {
        state = State.Idle
        pendingEnds = 0
        ended = false
        stopping = false
        discardBarrier = true
    }

    /** 释放时清空全部代际状态。 */
    @Synchronized
    fun reset() {
        state = State.Idle
        pendingEnds = 0
        ended = false
        stopping = false
        discardBarrier = false
    }

    enum class End { Replaced, Ended, Failed }
}

/** 一次 loadfile 的目标与代际；URL 与序号总是成对使用。 */
internal data class LoadSpec(val url: String, val token: Int)

/**
 * loadfile 与 FILE_LOADED 的代际关联：每次 start 入队在途加载，FILE_LOADED 按目标路径匹配；
 * 同 URL 重载无法按路径区分，迟到的旧事件仍可能命中新条目。
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

    /** 与 [path] 对应的在途加载；没有匹配项时为 null。 */
    @Synchronized
    fun pendingLoadFor(path: String?): LoadSpec? =
        pendingLoads.firstOrNull { isTargetPath(it.url, path) }

    /** 是否还有同 URL 的旧文件结束事件未消化；同 URL 重载靠它拒绝迟到的旧 FILE_LOADED。 */
    @Synchronized
    fun hasEndingFor(url: String): Boolean = endingLoads.any { it.url == url }

    /**
     * 校验后取出 FILE_LOADED。[path] 是 mpv 当前 path。
     * 连续切集时前一个目标可能还没发出 END_FILE，它的 FILE_LOADED 也不会再来：
     * 按 path 直接取出最终目标，把它前面的死代际移出待加载队列（结束事件仍由结束队列消化）。
     * 找不到匹配项时保留队列：停止后迟到的旧 FILE_LOADED 不能占掉新加载的位置。
     */
    @Synchronized
    fun takeFileLoaded(path: String?): LoadSpec? {
        val index = pendingLoads.indexOfFirst { isTargetPath(it.url, path) }
        if (index < 0) return null
        repeat(index) { pendingLoads.removeFirst() }
        val spec = pendingLoads.removeFirst()
        loaded = spec
        return spec
    }

    /** 已加载且仍是当前代际；旧代际的 FILE_LOADED 不满足，ready 不能上报。 */
    @Synchronized
    fun currentLoaded(): LoadSpec? = loaded?.takeIf { it === current }

    /** 下一条即将开始加载的文件；START_FILE 按命令顺序归属于队首。 */
    @Synchronized
    fun pendingStart(): LoadSpec? = pendingLoads.firstOrNull()

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
     * 丢弃不会再产生事件的死代际（加载看护恢复前）。
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
 * 一次 END_FILE 的判定结果与它归属的加载。
 * [spec] 为 null 表示队列里已找不到对应加载（丢弃死代际后的迟到结束），此时失败也不上报。
 */
internal data class LoadEnd(val end: MpvFileEvents.End, val spec: LoadSpec?)

/**
 * seek 与 ready 的序号：ready 带回的序号不小于该 seek 的序号，才算这一跳之后的信号。
 *
 * 序号在 seek 入队时预留（控制层靠它做加载就绪门槛），命令真正下发后才进待认队列，
 * 但要等 MPV_EVENT_SEEK 到达才把它设为“当前可接受序号”；
 * 这样即使旧文件的 restart/reconfig 在 seek 命令之后才被处理，它仍带旧序号，不会被误认。
 * 同一序号内，需等待真正出帧与无需确认的 ready 各只上报一次。
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

    /** 预留一个 seek 序号；被覆盖或作废的那一跳不会进待认队列，序号因此可以跳号。 */
    @Synchronized
    fun reserve(): Int {
        issued++
        return issued
    }

    /** seek 命令已下发：它的 MPV_EVENT_SEEK 到达前，ready 仍用旧序号。 */
    @Synchronized
    fun onSeekIssued(serial: Int) {
        pendingSeeks.addLast(serial)
    }

    /**
     * 改写后的再次下发：旧序号换成新序号。
     * 被替换的那条命令可能不再产生 SEEK 事件，按「一事件一序号」消费会让之后每次落定都错位；
     * 替换登记后，只来一条事件与两条事件都对齐到新序号。
     */
    @Synchronized
    fun onSeekReissued(previousSerial: Int, serial: Int) {
        pendingSeeks.remove(previousSerial)
        pendingSeeks.addLast(serial)
    }

    /** 命令下发失败：撤掉这条不会再有 SEEK 事件的序号，后续事件不会错位。 */
    @Synchronized
    fun discardIssued(serial: Int) {
        pendingSeeks.remove(serial)
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

    /** 加载看护恢复前丢弃死代际，并归零结束计数，避免吞掉恢复文件的自然结束。 */
    @Synchronized
    fun discardInFlight() {
        loads.discardInFlight()
        fileEvents.onDiscardInFlight()
    }

    /**
     * FILE_LOADED 归属：按 mpv path 找到在途加载。
     * 还有旧文件的 END_FILE 未消化时，只有同 URL 的迟到事件必须拒绝（path 无法区分新旧代际）；
     * 不同 URL 的最终目标可以提前接管，避免被取代目标的迟到 END_FILE 卡住连续切集。
     */
    @Synchronized
    fun onFileLoaded(path: String?): LoadSpec? {
        val pending = loads.pendingLoadFor(path) ?: return null
        if (fileEvents.hasPendingEnds && loads.hasEndingFor(pending.url)) return null
        val spec = loads.takeFileLoaded(path) ?: return null
        if (!fileEvents.onFileLoaded(isTarget = true)) return null
        return spec
    }

    @Synchronized
    fun onEndFile(): LoadEnd {
        val end = fileEvents.onEndFile()
        // 被替换的结束属于结束队列；当前文件的自然卸载与失败都要清掉当前代际，
        // 否则迟到的 ready 仍会被当成这轮加载的有效信号。
        val spec = loads.onEndFile(isReplaced = end == MpvFileEvents.End.Replaced)
        return LoadEnd(end, spec)
    }

    /** 下一条即将开始加载的令牌；START_FILE 的事件不带文件标识，按命令顺序归属队首。 */
    @Synchronized
    fun pendingLoadToken(): Int? = loads.pendingStart()?.token

    /** 预留本代的一个 seek 序号；ready 带回的序号与它相等才算这一跳之后的信号。 */
    @Synchronized
    fun reserveSeekSerial(): Int = readySignals.reserve()

    /** 刷新的 seek 命令下发失败：撤掉它预留的序号，避免后续 SEEK 事件消费错位。 */
    @Synchronized
    fun discardSeekSerial(serial: Int) {
        readySignals.discardIssued(serial)
    }

    /** 一条 seek 命令已下发：序号进入待认队列，ready 按它认这一跳。 */
    @Synchronized
    fun onSeekIssued(serial: Int) {
        readySignals.onSeekIssued(serial)
    }

    /** 改写后的再次下发：旧序号换成新序号，被替换的命令不再单独占一个待认位。 */
    @Synchronized
    fun onSeekReissued(previousSerial: Int, serial: Int) {
        readySignals.onSeekReissued(previousSerial, serial)
    }

    /** 一轮跳转开始：离开片尾与序号登记是两件事，每条真正下发的命令都要做一次。 */
    @Synchronized
    fun onSeekStarted() {
        fileEvents.onSeekIssued()
    }

    @Synchronized
    fun onSeekEvent() {
        readySignals.onSeekEvent()
    }

    /** 自然播完时返回产生它的加载令牌；旧代际或重复上报返回 null。 */
    @Synchronized
    fun onEofChanged(value: Boolean): Int? {
        if (!fileEvents.onEofChanged(value)) return null
        return loads.currentLoaded()?.token
    }

    /** 当前已加载的加载令牌；用于给出帧信号带上代际。 */
    @Synchronized
    fun currentLoadedToken(): Int? = loads.currentLoaded()?.token

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
