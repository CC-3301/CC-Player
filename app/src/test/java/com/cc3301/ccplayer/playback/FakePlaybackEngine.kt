package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import android.view.Surface

/**
 * 假播放引擎：记录控制层下发的指令，并让测试自己触发引擎回调。
 */
class FakePlaybackEngine : PlaybackEngine {

    private var listener: PlaybackEngine.Listener? = null

    val startedUrls = mutableListOf<String>()
    val startedPaused = mutableListOf<Boolean>()
    val startedPositions = mutableListOf<Long>()

    /** 指令顺序，用于验证先暂停、再抓帧、再换文件。 */
    val actions = mutableListOf<String>()

    /** 抓帧返回的画面；测试里保持 null，避免依赖 android.graphics 实现。 */
    var frameToGrab: Bitmap? = null
    var grabFrameCalls = 0
        private set
    var pauseCalls = 0
        private set
    var resumeCalls = 0
        private set
    var stopCalls = 0
        private set
    val seekPositions = mutableListOf<Long>()
    val fadeInDurations = mutableListOf<Long>()
    val decodeModes = mutableListOf<DecodeMode>()

    /** 当前媒体的内嵌字幕轨道，测试自己摆。 */
    var embeddedTracks: List<SubtitleTrack> = emptyList()

    val addedExternalSubtitles = mutableListOf<String>()
    val selectedEmbeddedTracks = mutableListOf<Int>()
    val selectedExternalSubtitles = mutableListOf<String>()

    var disableSubtitleCalls = 0
        private set

    override fun setListener(listener: PlaybackEngine.Listener) {
        this.listener = listener
    }

    override fun start(url: String, paused: Boolean, startPositionMillis: Long) {
        actions += "start"
        startedUrls += url
        startedPaused += paused
        startedPositions += startPositionMillis
    }

    override fun grabFrame(): Bitmap? {
        actions += "grab"
        grabFrameCalls++
        return frameToGrab
    }

    override fun pause() {
        actions += "pause"
        pauseCalls++
    }

    override fun resume() {
        actions += "resume"
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

    override fun embeddedSubtitleTracks(): List<SubtitleTrack> = embeddedTracks

    override fun addExternalSubtitle(location: String) {
        addedExternalSubtitles += location
    }

    override fun selectEmbeddedSubtitle(trackId: Int) {
        selectedEmbeddedTracks += trackId
    }

    override fun selectExternalSubtitle(location: String) {
        selectedExternalSubtitles += location
    }

    override fun disableSubtitles() {
        disableSubtitleCalls++
    }

    override fun attachSurface(surface: Surface) = Unit

    override fun detachSurface() = Unit

    override fun setSurfaceSize(width: Int, height: Int) = Unit

    override fun release() = Unit

    /** 模拟一次正常起播：文件已打开且首帧已就绪。 */
    fun reportMediaLoaded() {
        listener?.onMediaLoaded()
        listener?.onFirstFrameReady(fromVideoReconfig = false)
    }

    /** 只上报文件打开，首帧还没就绪。 */
    fun reportFileLoaded() = listener?.onMediaLoaded()

    /** 首帧已上屏（播放中的 PLAYBACK_RESTART）。 */
    fun reportFirstFrameReady() = listener?.onFirstFrameReady(fromVideoReconfig = false)

    /** 暂停态只收到视频链重配，首帧可能还没上屏。 */
    fun reportVideoReconfigured() = listener?.onFirstFrameReady(fromVideoReconfig = true)

    fun reportPauseChanged(paused: Boolean) = listener?.onPauseChanged(paused)

    fun reportPlaybackEnded() = listener?.onPlaybackEnded()

    fun reportPlaybackFailed() = listener?.onPlaybackFailed()

    fun reportPositionChanged(positionMillis: Long, durationMillis: Long) =
        listener?.onPositionChanged(positionMillis, durationMillis)

    fun reportVideoSizeChanged(width: Int, height: Int) = listener?.onVideoSizeChanged(width, height)
}
