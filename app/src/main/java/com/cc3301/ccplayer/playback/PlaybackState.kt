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

/** 首次加载缓冲：加载中且不在切集；此时只允许返回。 */
fun isInitialLoading(state: PlaybackState, switching: Boolean): Boolean =
    state == PlaybackState.Loading && !switching

/**
 * 一次播放失败：带入口代际与是否首次加载。
 * 界面只接受当前入口代际的首次加载失败，用于退回列表；其余失败只提示。
 */
data class PlaybackFailure(
    val generation: Int,
    val firstLoad: Boolean,
)
