package com.cc3301.ccplayer.playback

/**
 * 引擎的加载代际与结束事件判定。
 *
 * mpv 的事件不带文件标识：`loadfile replace` 会先给旧文件发一次 END_FILE，
 * 加载失败则只有 END_FILE、没有 FILE_LOADED。这里用“是否已加载当前文件”
 * 与“是否在等旧文件的替换结束”两条状态把三者区分开。
 */
internal class MpvFileEvents {

    /** 当前文件是否已收到 FILE_LOADED。 */
    private var loaded = false

    /** 下一次 END_FILE 属于被替换的旧文件。 */
    private var expectReplacementEnd = false

    /** 当前文件是否已到末尾（只接受当前代际的属性事件）。 */
    private var eof = false

    val isLoaded: Boolean get() = loaded

    /** 下发新的 loadfile；已加载的文件会先结束，用来吞掉它的 END_FILE。 */
    fun onStart() {
        expectReplacementEnd = loaded
        loaded = false
        eof = false
    }

    /** 收到 FILE_LOADED；替换旧文件时迟到的旧事件返回 false。 */
    fun onFileLoaded(): Boolean {
        if (expectReplacementEnd) return false
        loaded = true
        eof = false
        return true
    }

    /** 记录 eof-reached 属性；旧代际的迟到事件不采纳。 */
    fun onEofChanged(value: Boolean) {
        if (loaded) eof = value
    }

    /** 结束事件是否算自然播完；位置到达结尾时兜底 eof 属性未上报。 */
    fun onEndFile(atEndByPosition: Boolean): End {
        if (expectReplacementEnd) {
            expectReplacementEnd = false
            return End.Replaced
        }
        val natural = loaded && (eof || atEndByPosition)
        loaded = false
        eof = false
        return if (natural) End.Ended else End.Failed
    }

    /** 停止或释放时清空代际状态。 */
    fun reset() {
        loaded = false
        expectReplacementEnd = false
        eof = false
    }

    enum class End { Replaced, Ended, Failed }
}
