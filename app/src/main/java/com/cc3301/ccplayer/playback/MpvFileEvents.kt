package com.cc3301.ccplayer.playback

/**
 * 引擎的加载代际与结束事件判定。
 *
 * mpv 的事件不带文件标识：`loadfile replace` 会给被替换的旧文件发 END_FILE；
 * 新加载被中止时（例如切集中返回取消）也会给在途文件发 END_FILE；
 * 加载失败则只有 END_FILE、没有 FILE_LOADED。这里用“待吞的结束事件计数”
 * 把旧代际的结束事件先消耗掉，再用当前代际是否已 FILE_LOADED 判断自然结束或失败。
 *
 * 只在引擎事件线程访问，不做额外同步。
 */
internal class MpvFileEvents {

    private enum class State { Idle, Loading, Loaded, Stopping }

    private var state = State.Idle

    /**
     * 已被新 loadfile 命令取代、但还没收到 END_FILE 的旧文件数量。
     * 每个新 start 都会给上一个在途/已加载文件记一次。
     */
    private var pendingEnds = 0

    /** 当前文件是否已到末尾（只接受当前代际的属性事件）。 */
    private var eof = false

    val isLoaded: Boolean get() = state == State.Loaded

    /** 下发新的 loadfile：上一个在途或已加载文件之后会来一次 END_FILE。 */
    fun onStart() {
        if (state != State.Idle) pendingEnds++
        state = State.Loading
        eof = false
    }

    /** 主动停止：停止产生的 END_FILE 不算播放结束，也不触发失败。 */
    fun onStop() {
        if (state != State.Idle) state = State.Stopping
        eof = false
    }

    /**
     * 收到 FILE_LOADED；[isTarget] 为假表示迟到的是旧代际文件，直接忽略。
     * 当前代际迟到的旧事件不能把状态推进到 Loaded。
     */
    fun onFileLoaded(isTarget: Boolean): Boolean {
        if (!isTarget) return false
        state = State.Loaded
        eof = false
        return true
    }

    /** 记录 eof-reached 属性；旧代际的迟到事件不采纳。 */
    fun onEofChanged(value: Boolean) {
        if (state == State.Loaded) eof = value
    }

    /** 结束事件是否算自然播完；位置到达结尾时兜底 eof 属性未上报。 */
    fun onEndFile(atEndByPosition: Boolean): End {
        if (pendingEnds > 0) {
            pendingEnds--
            return End.Replaced
        }
        if (state == State.Stopping) {
            state = State.Idle
            return End.Replaced
        }
        val natural = state == State.Loaded && (eof || atEndByPosition)
        state = State.Idle
        eof = false
        return if (natural) End.Ended else End.Failed
    }

    /** 释放时清空全部代际状态。 */
    fun reset() {
        state = State.Idle
        pendingEnds = 0
        eof = false
    }

    enum class End { Replaced, Ended, Failed }
}
