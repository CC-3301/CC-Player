package com.cc3301.ccplayer.playback

/** 播放会话状态。 */
sealed interface PlaybackState {
    /** 没有打开的媒体。 */
    data object Idle : PlaybackState

    /** 已请求播放，引擎还没打开媒体。 */
    data object Loading : PlaybackState

    data object Playing : PlaybackState

    data object Paused : PlaybackState

    /** 当前媒体播放到末尾。 */
    data object Ended : PlaybackState
}
