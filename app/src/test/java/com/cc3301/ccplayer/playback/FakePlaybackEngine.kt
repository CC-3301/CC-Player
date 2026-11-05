package com.cc3301.ccplayer.playback

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 假播放引擎：记录控制层下发的指令，并让测试自己触发引擎回调。
 */
class FakePlaybackEngine : PlaybackEngine {

    private var listener: PlaybackEngine.Listener? = null

    val startedUrls = mutableListOf<String>()
    val startedPaused = mutableListOf<Boolean>()
    val startedPositions = mutableListOf<Long>()

    /** 指令顺序，用于验证先暂停、再换文件。 */
    val actions = mutableListOf<String>()

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

    /** 本代每次真正下发的命令序号，按顺序；预览改写后的再次下发也各占一条。 */
    val issuedSeekSerials = mutableListOf<Int>()

    /** 最近一次下发的 seek 身份；与真实引擎一致，进程级单调，加载不重置。 */
    var currentSeekId = 0L
        private set

    /** 回报过的 (seekId, 目标毫秒)，用于断言回执带的是哪一跳。 */
    val seekExecutions = mutableListOf<Pair<Long, Long>>()
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
        // 与真实引擎一致：加载只作废在途与待发 seek，seek 身份本身不归零；命令序号换代归零。
        seekInFlightId = null
        seekInFlightMillis = null
        seekInFlightPreview = false
        pendingSeekId = null
        pendingSeekMillis = null
        pendingSeekPreview = false
        currentSeekSerial = 0
        acceptedSeekSerial = 0
        issuedSeekSerials.clear()
        return ++currentLoadToken
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

    /** 在途这一跳的身份、序号、目标毫秒与是否预览目标；未下发或已执行完时为 null。 */
    private var seekInFlightId: Long? = null
    private var seekInFlightSerial = 0
    private var seekInFlightMillis: Long? = null
    private var seekInFlightPreview = false

    /** 在途期间被覆盖的最新待执行一跳；执行完补发一次。 */
    var pendingSeekId: Long? = null
        private set
    var pendingSeekMillis: Long? = null
        private set
    private var pendingSeekSerial = 0
    private var pendingSeekPreview = false

    /** 本代加载内的 seek 序号：每下发一跳加一，加载换代归零；ready 带回同一个序号。 */
    private var currentSeekSerial = 0

    /** 本代最后一跳已回报执行的序号；本代还没有 seek 执行完时为 0。 */
    private var acceptedSeekSerial = 0

    override fun seekTo(positionMillis: Long): PlaybackEngine.SeekRequest =
        newSeek(positionMillis, preview = false)

    /** 新的一跳：在途时只覆盖待执行目标，不堆命令；返回的是这一跳自己的凭据。 */
    private fun newSeek(positionMillis: Long, preview: Boolean): PlaybackEngine.SeekRequest {
        val seekId = ++currentSeekId
        val serial = ++currentSeekSerial
        if (seekInFlightId != null) {
            pendingSeekId = seekId
            pendingSeekSerial = serial
            pendingSeekMillis = positionMillis
            pendingSeekPreview = preview
            return PlaybackEngine.SeekRequest(seekId, serial)
        }
        issueSeek(seekId, serial, positionMillis, preview)
        return PlaybackEngine.SeekRequest(seekId, serial)
    }

    /**
     * 与真实引擎一致：只有预览条目仍在执行时就地改写，待执行的预览一并丢弃，身份不变、序号换新，
     * 返回改写前的序号；旧序号的那条 ready 因此对不上这一跳。
     * 在途不是预览条目时按新的一跳入队；普通跳转的待执行目标不随改写丢弃。
     */
    override fun previewSeekTo(positionMillis: Long): PlaybackEngine.SeekRequest {
        val inFlightId = seekInFlightId
        if (inFlightId == null || !seekInFlightPreview) return newSeek(positionMillis, preview = true)
        val previousSerial = seekInFlightSerial
        if (pendingSeekPreview) {
            pendingSeekId = null
            pendingSeekMillis = null
            pendingSeekPreview = false
        }
        issueSeek(inFlightId, ++currentSeekSerial, positionMillis, preview = true)
        return PlaybackEngine.SeekRequest(inFlightId, previousSerial)
    }

    /** 一条 ready（PLAYBACK_RESTART）：[serial] 是它所属的命令序号，与在途那一跳不符时丢弃。 */
    fun reportReadyForSerial(serial: Int, loadToken: Int = currentLoadToken) {
        listener?.onLoadActivity(LoadActivity.PlaybackRestart, loadToken)
        // 在途为空（已结算）或序号对不上时，这条 ready 不属于这一跳。
        if (seekInFlightId == null || serial != seekInFlightSerial) return
        completeSeek()
    }

    private fun issueSeek(seekId: Long, serial: Int, positionMillis: Long, preview: Boolean = false) {
        seekPositions += positionMillis
        seekAudioCalls += "seek($positionMillis)"
        issuedSeekSerials += serial
        seekInFlightId = seekId
        seekInFlightSerial = serial
        seekInFlightMillis = positionMillis
        seekInFlightPreview = preview
    }

    /** 与真实引擎一致：在途这一跳执行完后回报它自己的身份与目标，并补发待执行的一跳。 */
    private fun completeSeek() {
        val executedId = seekInFlightId ?: return
        val executedMillis = seekInFlightMillis ?: return
        val executedSerial = seekInFlightSerial
        seekInFlightId = null
        seekInFlightMillis = null
        seekInFlightPreview = false
        acceptedSeekSerial = executedSerial
        seekExecutions += executedId to executedMillis
        listener?.onSeekExecuted(executedId, executedMillis)
        val pendingId = pendingSeekId ?: return
        val pendingMillis = pendingSeekMillis ?: return
        val pendingSerial = pendingSeekSerial
        val pendingPreview = pendingSeekPreview
        pendingSeekId = null
        pendingSeekMillis = null
        pendingSeekPreview = false
        issueSeek(pendingId, pendingSerial, pendingMillis, pendingPreview)
    }

    /** 模拟播放核心的在途 seek 超时：回报这一跳已执行并补发待执行目标。 */
    fun reportSeekExecuted() = completeSeek()

    /** 直接投一条回执，模拟旧代或旧跳的迟到回报。 */
    fun reportSeekExecuted(seekId: Long, targetMillis: Long) {
        listener?.onSeekExecuted(seekId, targetMillis)
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

    /** 位置中性精确刷新的次数；刷新不改变播放位置，也不占 seek 序号。 */
    var refreshFrameCalls = 0
        private set

    /** 刷新是否模拟播放核心重解出帧并补报一次帧上屏；关掉用于覆盖回执没来的兜底。 */
    var refreshEmitsFrame = true

    /** 设置后刷新回执按测试调度器异步投递，覆盖真实引擎的异步回执链。 */
    var callbackScope: CoroutineScope? = null

    override fun refreshFrame() {
        refreshFrameCalls++
        if (!refreshEmitsFrame) return
        val target = listener ?: return
        val token = currentLoadToken
        val scope = callbackScope
        if (scope != null) scope.launch { target.onFramePresented(token) } else target.onFramePresented(token)
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
        // 续播 seek 就发生在 onMediaLoaded 里：先回报这一跳已执行，再把它的序号带进 ready。
        completeSeek()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = false,
            targetUrl = targetUrl,
            seekSerial = acceptedSeekSerial,
            loadToken = loadToken,
        )
    }

    /** 只上报文件打开，首帧还没就绪。 */
    fun reportFileLoaded(targetUrl: String? = currentTargetUrl, loadToken: Int = currentLoadToken) {
        listener?.onLoadActivity(LoadActivity.FileLoaded, loadToken)
        listener?.onMediaLoaded(targetUrl, loadToken, hasVideoTrack)
    }

    /**
     * 首帧已上屏（播放中的 PLAYBACK_RESTART）。
     * [fromSeek] 为假表示这条信号是加载自身的结果、不对应任何 seek，也不会结算在途那一跳。
     */
    fun reportFirstFrameReady(
        targetUrl: String? = currentTargetUrl,
        fromSeek: Boolean = true,
        loadToken: Int = currentLoadToken,
    ) {
        listener?.onLoadActivity(LoadActivity.PlaybackRestart, loadToken)
        if (fromSeek) completeSeek()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = false,
            targetUrl = targetUrl,
            seekSerial = acceptedSeekSerial,
            loadToken = loadToken,
        )
    }

    /** 暂停态只收到视频链重配，首帧可能还没上屏。 */
    fun reportVideoReconfigured(
        targetUrl: String? = currentTargetUrl,
        fromSeek: Boolean = true,
        loadToken: Int = currentLoadToken,
    ) {
        listener?.onLoadActivity(LoadActivity.VideoReconfig, loadToken)
        if (fromSeek) completeSeek()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = true,
            targetUrl = targetUrl,
            seekSerial = acceptedSeekSerial,
            loadToken = loadToken,
        )
    }

    /** 暂停态下的 PLAYBACK_RESTART：不能证明画面已经上屏。 */
    fun reportPausedRestart(
        targetUrl: String? = currentTargetUrl,
        fromSeek: Boolean = true,
        loadToken: Int = currentLoadToken,
    ) {
        listener?.onLoadActivity(LoadActivity.PlaybackRestart, loadToken)
        if (fromSeek) completeSeek()
        listener?.onFirstFrameReady(
            needsFrameConfirmation = true,
            targetUrl = targetUrl,
            seekSerial = acceptedSeekSerial,
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
