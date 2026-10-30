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

    /** resume 是否模拟真实引擎产生一次加载事件；用于覆盖 0 次、1 次与多次回声。 */
    var resumeEmitsActivity = true
    var stopCalls = 0
        private set
    var discardInFlightCalls = 0
        private set
    val seekPositions = mutableListOf<Long>()

    /** 最近一次 seek 的序号；ready 回调默认带上它。 */
    var currentSeekSerial = 0
        private set
    val fadeInDurations = mutableListOf<Long>()

    /** 静音指令序列：true 为静音，false 为恢复。 */
    val muteCalls = mutableListOf<Boolean>()

    /** seek、静音与淡入的调用顺序，用于断言收尾顺序。 */
    val seekAudioCalls = mutableListOf<String>()
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
        // 与真实引擎一致：每次加载把 seek 序号归零，在途与待发 seek 一并作废。
        currentSeekSerial = 0
        seekInFlightSerial = null
        pendingSeekMillis = null
        return ++currentLoadToken
    }

    override fun grabFrame(): PlaybackEngine.Frame? {
        actions += "grab"
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
        // 与真实引擎一致：恢复播放会产生一次加载事件；测试可关掉以模拟 0 次事件。
        if (resumeEmitsActivity) {
            listener?.onLoadActivity(LoadActivity.PlaybackRestart, currentLoadToken)
        }
    }

    override fun stop() {
        stopCalls++
    }

    override fun discardInFlightLoads() {
        discardInFlightCalls++
    }

    /** 在途 seek 的序号；未就绪前新目标只覆盖 [pendingSeekMillis]。 */
    private var seekInFlightSerial: Int? = null

    /** 在途期间被覆盖的最新待发目标；就绪后补发一次。 */
    var pendingSeekMillis: Long? = null
        private set

    override fun seekTo(positionMillis: Long): Int {
        val inFlight = seekInFlightSerial
        if (inFlight != null) {
            // 与真实引擎一致：上一发未就绪时只覆盖待发目标，不堆命令。
            pendingSeekMillis = positionMillis
            return inFlight
        }
        return issueSeek(positionMillis)
    }

    private fun issueSeek(positionMillis: Long): Int {
        seekPositions += positionMillis
        seekAudioCalls += "seek($positionMillis)"
        return (++currentSeekSerial).also { seekInFlightSerial = it }
    }

    /** 就绪：放开在途槽位并补发被覆盖的目标。 */
    private fun flushPendingSeek() {
        seekInFlightSerial = null
        val pending = pendingSeekMillis ?: return
        pendingSeekMillis = null
        issueSeek(pending)
    }

    override fun fadeInAudio(durationMillis: Long) {
        fadeInDurations += durationMillis
        seekAudioCalls += "fade($durationMillis)"
    }

    override fun setAudioMuted(muted: Boolean) {
        muteCalls += muted
        seekAudioCalls += "muted($muted)"
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

    private var attachedSurface: Surface? = null

    /** 视频输出翻转次数；用于断言 3 秒无新帧只翻转一次。 */
    var flipVideoOutputCalls = 0
        private set

    override fun attachSurface(surface: Surface) {
        if (attachedSurface === surface) return
        attachedSurface = surface
    }

    override fun startVideoOutput() = Unit

    override fun flipVideoOutput() {
        flipVideoOutputCalls++
    }

    override fun detachSurface() {
        attachedSurface = null
    }

    override fun setSurfaceSize(width: Int, height: Int) = Unit

    override fun release() = Unit

    /** 当前目标是否有视频轨；纯音频没有可等待的新视频帧。 */
    var hasVideoTrack: Boolean = true

    /** 模拟一次正常起播：文件已打开且首帧已就绪。 */
    fun reportMediaLoaded(targetUrl: String? = currentTargetUrl, loadToken: Int = currentLoadToken) {
        listener?.onLoadActivity(LoadActivity.FileLoaded, loadToken)
        listener?.onMediaLoaded(targetUrl, loadToken, hasVideoTrack)
        // seek 序号在 onMediaLoaded 之后读取：续播 seek 就发生在那里。
        flushPendingSeek()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = false,
            targetUrl = targetUrl,
            seekSerial = currentSeekSerial,
            loadToken = loadToken,
        )
    }

    /** 只上报文件打开，首帧还没就绪。 */
    fun reportFileLoaded(targetUrl: String? = currentTargetUrl, loadToken: Int = currentLoadToken) {
        listener?.onLoadActivity(LoadActivity.FileLoaded, loadToken)
        listener?.onMediaLoaded(targetUrl, loadToken, hasVideoTrack)
    }

    /** 首帧已上屏（播放中的 PLAYBACK_RESTART）。 */
    fun reportFirstFrameReady(
        targetUrl: String? = currentTargetUrl,
        seekSerial: Int = currentSeekSerial,
        loadToken: Int = currentLoadToken,
    ) {
        listener?.onLoadActivity(LoadActivity.PlaybackRestart, loadToken)
        flushPendingSeek()
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
        listener?.onLoadActivity(LoadActivity.VideoReconfig, loadToken)
        flushPendingSeek()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = true,
            targetUrl = targetUrl,
            seekSerial = seekSerial,
            loadToken = loadToken,
        )
    }

    /** 暂停态下的 PLAYBACK_RESTART：不能证明画面已经上屏。 */
    fun reportPausedRestart(
        targetUrl: String? = currentTargetUrl,
        seekSerial: Int = currentSeekSerial,
        loadToken: Int = currentLoadToken,
    ) {
        listener?.onLoadActivity(LoadActivity.PlaybackRestart, loadToken)
        flushPendingSeek()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = true,
            targetUrl = targetUrl,
            seekSerial = seekSerial,
            loadToken = loadToken,
        )
    }

    /** 只上报加载活动，不给 ready/end。 */
    fun reportLoadActivity(
        activity: LoadActivity = LoadActivity.Seek,
        loadToken: Int = currentLoadToken,
    ) = listener?.onLoadActivity(activity, loadToken)

    /** 真正出帧：加载就绪后播放位置前进。 */
    fun reportFramePresented(loadToken: Int = currentLoadToken) =
        listener?.onFramePresented(loadToken)

    fun reportPauseChanged(paused: Boolean) = listener?.onPauseChanged(paused)

    fun reportPlaybackEnded(loadToken: Int = currentLoadToken) {
        listener?.onLoadActivity(LoadActivity.EndFile, loadToken)
        listener?.onPlaybackEnded(loadToken)
    }

    fun reportPlaybackFailed(errorText: String? = null, loadToken: Int = currentLoadToken) {
        listener?.onLoadActivity(LoadActivity.EndFile, loadToken)
        listener?.onPlaybackFailed(loadToken, errorText)
    }

    fun reportPositionChanged(positionMillis: Long, durationMillis: Long) =
        listener?.onPositionChanged(positionMillis, durationMillis)

    fun reportVideoSizeChanged(width: Int, height: Int) = listener?.onVideoSizeChanged(width, height)
}
