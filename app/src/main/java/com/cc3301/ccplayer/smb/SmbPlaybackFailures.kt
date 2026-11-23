package com.cc3301.ccplayer.smb

/** 播放读失败的分类：告诉播放控制层该换传输恢复还是按网络／服务器终态收尾。 */
enum class SmbPlaybackFailure {
    /** 已连上的传输读不出数据：本地 SMB 异常，换传输才能恢复。 */
    Transport,

    /** 打开失败且可判为连不上：网络／服务器故障。 */
    Unreachable,
}

/**
 * 播放这一路读的最近一次失败，按媒体位置记一条。
 *
 * 记录只反映还没恢复的那次失败：播放读打开成功即清掉该位置的记录。
 * 浏览、封面与探测的读不记：它们不影响播放的传输恢复判定。
 */
internal class SmbPlaybackFailures {

    private val lock = Any()

    private val failures = HashMap<String, SmbPlaybackFailure>()

    fun note(location: String, failure: SmbPlaybackFailure) {
        synchronized(lock) { failures[location] = failure }
    }

    fun clear(location: String) {
        synchronized(lock) { failures.remove(location) }
    }

    fun classify(location: String): SmbPlaybackFailure? = synchronized(lock) { failures[location] }
}
