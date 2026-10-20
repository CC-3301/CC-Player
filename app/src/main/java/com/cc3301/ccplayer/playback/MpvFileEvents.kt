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

    @get:Synchronized
    val isLoaded: Boolean get() = state == State.Loaded

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

    /** 释放时清空全部代际状态。 */
    @Synchronized
    fun reset() {
        state = State.Idle
        pendingEnds = 0
        eof = false
        stopping = false
    }

    enum class End { Replaced, Ended, Failed }
}

/**
 * seek 与 ready 的序号：控制层只认续播 seek 之后的信号。
 *
 * seek 命令下发时先登记序号，但要等 MPV_EVENT_SEEK 到达才把它设为“当前可接受序号”。
 * 这样即使旧文件的 restart/reconfig 在 seek 命令之后才被处理，它仍带旧序号，不会被误认。
 * 同一序号内重配与重播各只上报一次。控制层线程与 mpv 事件线程都会调用，方法同步。
 */
internal class MpvReadySignals {

    private var issued = 0
    private val pendingSeeks = ArrayDeque<Int>()
    private var accepted = 0
    private var lastReconfigSerial = -1
    private var lastRestartSerial = -1

    /** 新文件开始加载：ready 判定从当前已接受的序号重新开始。 */
    @Synchronized
    fun onStart() {
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
    fun acceptReady(fromVideoReconfig: Boolean): Int? {
        if (fromVideoReconfig) {
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
