package com.cc3301.ccplayer.playback

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

    /** 抓帧返回的画面；测试用不含位图的 Frame 模拟“有帧”。 */
    var frameToGrab: PlaybackEngine.Frame? = null
    var grabFrameCalls = 0
        private set

    /** 最近一次 start 的目标，ready 回调带上它供控制层过滤迟到事件。 */
    var currentTargetUrl: String? = null
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
        currentTargetUrl = url
    }

    override fun grabFrame(): PlaybackEngine.Frame? {
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
        listener?.onLoadActivity()
        listener?.onMediaLoaded()
        listener?.onFirstFrameReady(fromVideoReconfig = false, targetUrl = currentTargetUrl)
    }

    /** 只上报文件打开，首帧还没就绪。 */
    fun reportFileLoaded() {
        listener?.onLoadActivity()
        listener?.onMediaLoaded()
    }

    /** 首帧已上屏（播放中的 PLAYBACK_RESTART）。 */
    fun reportFirstFrameReady(targetUrl: String? = currentTargetUrl) {
        listener?.onLoadActivity()
        listener?.onFirstFrameReady(fromVideoReconfig = false, targetUrl = targetUrl)
    }

    /** 暂停态只收到视频链重配，首帧可能还没上屏。 */
    fun reportVideoReconfigured(targetUrl: String? = currentTargetUrl) {
        listener?.onLoadActivity()
        listener?.onFirstFrameReady(fromVideoReconfig = true, targetUrl = targetUrl)
    }

    /** 只上报加载活动，不给 ready/end。 */
    fun reportLoadActivity() = listener?.onLoadActivity()

    fun reportPauseChanged(paused: Boolean) = listener?.onPauseChanged(paused)

    fun reportPlaybackEnded() {
        listener?.onLoadActivity()
        listener?.onPlaybackEnded()
    }

    fun reportPlaybackFailed() {
        listener?.onLoadActivity()
        listener?.onPlaybackFailed()
    }

    fun reportPositionChanged(positionMillis: Long, durationMillis: Long) =
        listener?.onPositionChanged(positionMillis, durationMillis)

    fun reportVideoSizeChanged(width: Int, height: Int) = listener?.onVideoSizeChanged(width, height)
}
