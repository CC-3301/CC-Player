package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.ccplayer.playback.PlaybackState

/**
 * 浏览页进入播放页的瞬时状态：准备期与首次加载期间只允许返回。
 *
 * [enter] 为真时先显示缓冲指示；播放控制层接管缓冲或首次加载就绪后解除。
 * 首次加载失败只消费一次，供界面退回列表。
 */
class PlaybackEntryState {

    var openingPlayback by mutableStateOf(false)
        private set

    var awaitingInitialPlayback by mutableStateOf(false)
        private set

    /** 进入播放页；[preparing] 为真时保持缓冲指示并限制输入。 */
    fun enter(preparing: Boolean) {
        openingPlayback = preparing
        awaitingInitialPlayback = preparing
    }

    /** 播放控制层上报状态后同步准备态。 */
    fun onPlaybackUpdate(playbackState: PlaybackState, buffering: Boolean, switching: Boolean) {
        if (openingPlayback && (switching || buffering || playbackState.isReady)) {
            openingPlayback = false
        }
        if (switching || playbackState.isReady) {
            awaitingInitialPlayback = false
        }
    }

    /** 首次播放加载失败：返回是否应退回列表，并清除等待态。 */
    fun consumeInitialFailure(): Boolean {
        if (!awaitingInitialPlayback) return false
        awaitingInitialPlayback = false
        return true
    }

    /** 准备期与首次加载（非切集）只允许返回。 */
    fun initialLoading(playbackState: PlaybackState, switching: Boolean): Boolean =
        openingPlayback || (playbackState == PlaybackState.Loading && !switching)

    /** 离开播放页时清除准备态。 */
    fun reset() {
        openingPlayback = false
        awaitingInitialPlayback = false
    }

    private val PlaybackState.isReady: Boolean
        get() = this == PlaybackState.Playing || this == PlaybackState.Paused
}
