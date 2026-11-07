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

/** 位置距片尾多近仍算片尾。 */
const val EPISODE_END_TOLERANCE_MILLIS = 1_000L

/** 片尾的统一判据：已结束，或暂停在片尾位置。 */
fun isAtEpisodeEnd(state: PlaybackState, positionMillis: Long, durationMillis: Long): Boolean =
    state == PlaybackState.Ended ||
        (
            state == PlaybackState.Paused &&
                durationMillis > 0 &&
                positionMillis >= durationMillis - EPISODE_END_TOLERANCE_MILLIS
            )
