package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * 假播放引擎：记录控制层下发的指令，并让测试自己触发引擎回调。
 */
class FakePlaybackEngine : PlaybackEngine {

    private var listener: PlaybackEngine.Listener? = null

    val startedUrls = mutableListOf<String>()
    var pauseCalls = 0
        private set
    var resumeCalls = 0
        private set
    var stopCalls = 0
        private set
    val seekPositions = mutableListOf<Long>()
    val fadeInDurations = mutableListOf<Long>()
    val decodeModes = mutableListOf<DecodeMode>()

    override fun setListener(listener: PlaybackEngine.Listener) {
        this.listener = listener
    }

    override fun start(url: String) {
        startedUrls += url
    }

    override fun pause() {
        pauseCalls++
    }

    override fun resume() {
        resumeCalls++
    }

    override fun stop() {
        stopCalls++
    }

    override fun seekTo(positionMillis: Long) {
        seekPositions += positionMillis
    }

    override fun fadeInAudio(durationMillis: Long) {
        fadeInDurations += durationMillis
    }

    override fun setDecodeMode(mode: DecodeMode) {
        decodeModes += mode
    }

    override fun attachSurface(surface: Surface) = Unit

    override fun detachSurface() = Unit

    override fun setSurfaceSize(width: Int, height: Int) = Unit

    override fun release() = Unit

    fun reportMediaLoaded() = listener?.onMediaLoaded()

    fun reportPauseChanged(paused: Boolean) = listener?.onPauseChanged(paused)

    fun reportPlaybackEnded() = listener?.onPlaybackEnded()

    fun reportPositionChanged(positionMillis: Long, durationMillis: Long) =
        listener?.onPositionChanged(positionMillis, durationMillis)
}
