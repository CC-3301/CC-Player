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

    /** 抓帧前执行，用于模拟不可中断的原生调用。 */
    var beforeGrab: (() -> Unit)? = null

    /** 最近一次 start 的目标与代际，回调带上它们供控制层过滤迟到事件。 */
    var currentTargetUrl: String? = null
        private set
    var currentLoadToken = 0
        private set
    var pauseCalls = 0
        private set
    var resumeCalls = 0
        private set
    var stopCalls = 0
        private set
    var discardInFlightCalls = 0
        private set
    val seekPositions = mutableListOf<Long>()

    /** 最近一次 seek 的序号；ready 回调默认带上它。 */
    var currentSeekSerial = 0
        private set
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

    override fun start(url: String, paused: Boolean, startPositionMillis: Long): Int {
        actions += "start"
        startedUrls += url
        startedPaused += paused
        startedPositions += startPositionMillis
        currentTargetUrl = url
        return ++currentLoadToken
    }

    override fun grabFrame(): PlaybackEngine.Frame? {
        actions += "grab"
        grabFrameCalls++
        beforeGrab?.invoke()
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

    override fun discardInFlightLoads() {
        discardInFlightCalls++
    }

    override fun seekTo(positionMillis: Long): Int {
        seekPositions += positionMillis
        return ++currentSeekSerial
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

    /** 重新挂载 Surface 时递增，模拟引擎的挂载代数。 */
    override var surfaceGeneration: Int = 0
        private set

    /** 模拟一次重新挂载 Surface。 */
    fun reportSurfaceReattached() {
        surfaceGeneration++
    }

    private var attachedSurface: Surface? = null

    override fun attachSurface(surface: Surface) {
        if (attachedSurface === surface) return
        attachedSurface = surface
        surfaceGeneration++
    }

    override fun detachSurface() {
        attachedSurface = null
    }

    override fun setSurfaceSize(width: Int, height: Int) = Unit

    override fun release() = Unit

    /** 模拟一次正常起播：文件已打开且首帧已就绪。 */
    fun reportMediaLoaded(targetUrl: String? = currentTargetUrl, loadToken: Int = currentLoadToken) {
        listener?.onLoadActivity()
        listener?.onMediaLoaded(targetUrl, loadToken)
        // seek 序号在 onMediaLoaded 之后读取：续播 seek 就发生在那里。
        listener?.onFirstFrameReady(
            needsFrameConfirmation = false,
            targetUrl = targetUrl,
            seekSerial = currentSeekSerial,
            loadToken = loadToken,
        )
    }

    /** 只上报文件打开，首帧还没就绪。 */
    fun reportFileLoaded(targetUrl: String? = currentTargetUrl, loadToken: Int = currentLoadToken) {
        listener?.onLoadActivity()
        listener?.onMediaLoaded(targetUrl, loadToken)
    }

    /** 首帧已上屏（播放中的 PLAYBACK_RESTART）。 */
    fun reportFirstFrameReady(
        targetUrl: String? = currentTargetUrl,
        seekSerial: Int = currentSeekSerial,
        loadToken: Int = currentLoadToken,
    ) {
        listener?.onLoadActivity()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = false,
            targetUrl = targetUrl,
            seekSerial = seekSerial,
            loadToken = loadToken,
        )
    }

    /** 暂停态只收到视频链重配，首帧可能还没上屏。 */
    fun reportVideoReconfigured(
        targetUrl: String? = currentTargetUrl,
        seekSerial: Int = currentSeekSerial,
        loadToken: Int = currentLoadToken,
    ) {
        listener?.onLoadActivity()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = true,
            targetUrl = targetUrl,
            seekSerial = seekSerial,
            loadToken = loadToken,
        )
    }

    /** 暂停态下的 PLAYBACK_RESTART：同样需要控制层抓帧确认。 */
    fun reportPausedRestart(
        targetUrl: String? = currentTargetUrl,
        seekSerial: Int = currentSeekSerial,
        loadToken: Int = currentLoadToken,
    ) {
        listener?.onLoadActivity()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = true,
            targetUrl = targetUrl,
            seekSerial = seekSerial,
            loadToken = loadToken,
        )
    }

    /** 只上报加载活动，不给 ready/end。 */
    fun reportLoadActivity() = listener?.onLoadActivity()

    /** 播放态重新出帧。 */
    fun reportFramePresented(generation: Int = surfaceGeneration) =
        listener?.onFramePresented(generation)

    /** 暂停态视频输出重配，播放核心要求抓帧确认。 */
    fun reportSurfaceReconfigured(generation: Int = surfaceGeneration) =
        listener?.onSurfaceReconfigured(generation)

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
