package com.cc3301.ccplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放控制层：把播放指令翻译成引擎调用，把引擎回调汇成 [PlaybackState]。
 */
class PlaybackController(private val engine: PlaybackEngine) : PlaybackEngine.Listener {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** 会话里是否有已打开的媒体；没有时忽略控制指令与引擎回调。 */
    private var active = false

    init {
        engine.setListener(this)
    }

    fun open(url: String) {
        active = true
        _state.value = PlaybackState.Loading
        engine.start(url)
    }

    fun pause() {
        if (active) engine.pause()
    }

    fun resume() {
        if (active) engine.resume()
    }

    fun togglePlayPause() {
        when (_state.value) {
            PlaybackState.Playing -> pause()
            PlaybackState.Paused -> resume()
            else -> Unit
        }
    }

    fun stop() {
        active = false
        engine.stop()
        _state.value = PlaybackState.Idle
    }

    override fun onMediaLoaded() {
        if (!active) return
        _state.value = PlaybackState.Playing
    }

    override fun onPauseChanged(paused: Boolean) {
        if (!active) return
        _state.value = if (paused) PlaybackState.Paused else PlaybackState.Playing
    }

    override fun onPlaybackEnded() {
        if (!active) return
        active = false
        _state.value = PlaybackState.Ended
    }
}
