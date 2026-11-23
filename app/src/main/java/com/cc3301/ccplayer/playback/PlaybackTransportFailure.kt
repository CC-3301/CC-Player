package com.cc3301.ccplayer.playback

/**
 * 播放这一路读的失败分类：决定换传输恢复还是走既有失败收尾。
 *
 * 分类由网络源在失败现场判定，播放控制层只看结论；播放核心的失败信号本身不带这个信息。
 */
enum class PlaybackTransportFailure {
    /** 已连上的传输读失败或会话失效：换传输后按真实目标与位置恢复。 */
    Transport,

    /** 新传输仍连不上，可判为网络／服务器故障：走既有失败收尾。 */
    Unreachable,
}
