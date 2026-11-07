package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.isInitialLoading

/**
 * 浏览页进入播放页的瞬时状态：准备期与首次加载期间只放行不依赖加载的控件。
 *
 * [enter] 为真时先显示缓冲指示；播放控制层接管缓冲或首次加载就绪后解除。
 * 首次加载失败只消费一次，供界面退回列表。
 */
class PlaybackEntryState {

    var openingPlayback by mutableStateOf(false)
        private set

    var awaitingInitialPlayback by mutableStateOf(false)
        private set

    /**
     * 进入播放页；[preparing] 为真时保持缓冲指示并限制输入。
     * 视频即时进入才等待首次加载失败退回；音频保持原行为，首播失败留在播放页。
     */
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

    /** 准备期或播放控制层在缓冲时显示缓冲指示。 */
    fun showingBuffering(controllerBuffering: Boolean): Boolean = controllerBuffering || openingPlayback

    /** 首次播放加载失败：返回是否应退回列表，并清除等待态。 */
    fun consumeInitialFailure(): Boolean {
        if (!awaitingInitialPlayback) return false
        awaitingInitialPlayback = false
        return true
    }

    /** 准备期与首次加载（非切集）：除返回与不依赖加载的控件外，其余按加载态拦截。 */
    fun initialLoading(playbackState: PlaybackState, switching: Boolean): Boolean =
        openingPlayback || isInitialLoading(playbackState, switching)

    /** 离开播放页时清除准备态。 */
    fun reset() {
        openingPlayback = false
        awaitingInitialPlayback = false
    }

    private val PlaybackState.isReady: Boolean
        get() = this == PlaybackState.Playing || this == PlaybackState.Paused
}
