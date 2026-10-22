package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.compareNatural
import com.cc3301.ccplayer.progress.MediaProgress
import com.cc3301.ccplayer.progress.PlaybackProgressRepository
import com.cc3301.ccplayer.settings.ResumeMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 播放控制层：把播放指令翻译成引擎调用，把引擎回调汇成 [PlaybackState]。
 *
 * 播放列表取自当前目录的媒体文件，恒按名称自然排序（ADR-0007）；上一集/下一集取该序列的相邻项，
 * 与列表视图当前的排序无关。当前媒体播完后按循环模式与自动连播开关决定下一步，
 * 自然播完的下一集与手动切集共用同一切换与就绪判定（ADR-0024）。
 * 循环模式与解码模式的快捷切换只作用于本次会话。
 *
 * 起播位置按 [ResumeMode] 取上次位置或从头；播放中按间隔落进度记录，位置到达时长（播到 100%）时清除记录。
 *
 * 定时暂停按 [SleepTimerOption] 到点暂停，或等当前文件自然播完时暂停（ADR-0015）；定时随会话结束清除。
 *
 * 所有字段只在控制层线程访问：生产环境由 PlaybackService 以 marshalCallbacks 注入回调投递，
 * 引擎事件统一落到 Main，界面调用本来就在 Main，因此无需额外同步。
 */
class PlaybackController(
    private val engine: PlaybackEngine,
    private val progressStore: PlaybackProgressRepository,
    private val scope: CoroutineScope,
    /** 引擎回调来自 mpv 线程；真机上把它们统一投递到控制层所在的线程。 */
    marshalCallbacks: Boolean = false,
    /** 抓帧会进入 mpv 原生调用，不能占用控制层所在的主线程。 */
    private val frameProbeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** 诊断日志出口；默认不写。 */
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) : PlaybackEngine.Listener {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _playlist = MutableStateFlow<List<MediaFile>>(emptyList())
    val playlist: StateFlow<List<MediaFile>> = _playlist.asStateFlow()

    /** 当前播放在 [playlist] 里的下标；没有播放时是 -1。 */
    private val _currentIndex = MutableStateFlow(NO_MEDIA)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /** 拖动进度条时预览的目标位置；不在拖动时为 null。 */
    private val _seekPreviewMillis = MutableStateFlow<Long?>(null)
    val seekPreviewMillis: StateFlow<Long?> = _seekPreviewMillis.asStateFlow()

    /** 正在切集：原视频暂停，画面冻结，等目标就绪后替换；恢复原片期间仍为真。 */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /** 正在恢复原片：只剩播放/暂停在切集门禁之外额外放行。 */
    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    /** 缓冲指示是否显示：加载超过 [BUFFER_INDICATOR_DELAY_MILLIS] 仍未就绪时为真。 */
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    /** 切集期间盖在画面上的冻结帧；没有切集或已就绪时为 null。 */
    private val _freezeFrame = MutableStateFlow<Bitmap?>(null)
    val freezeFrame: StateFlow<Bitmap?> = _freezeFrame.asStateFlow()

    /**
     * 片尾恢复就绪后保持黑底，直到播放/暂停、seek、新加载或 stop。
     * 片尾没有首帧可显示，不能用冻结画面占位。
     */
    private val _holdBlack = MutableStateFlow(false)
    val holdBlack: StateFlow<Boolean> = _holdBlack.asStateFlow()

    /**
     * 当前是否有可渲染的 Surface。退到后台后为假：此时抓不到帧，
     * 切集以文件加载完成作为就绪，回前台由 Surface 重挂恢复画面。
     */
    private val _renderSurfaceAvailable = MutableStateFlow(true)
    val renderSurfaceAvailable: StateFlow<Boolean> = _renderSurfaceAvailable.asStateFlow()

    /**
     * 暂停态退到后台前缓存的画面。回前台先盖在视频视图上，
     * 等播放核心重新输出画面后撤掉，避免恢复期间黑屏。
     */
    private val _backgroundRestoreFrame = MutableStateFlow<Bitmap?>(null)
    val backgroundRestoreFrame: StateFlow<Bitmap?> = _backgroundRestoreFrame.asStateFlow()

    /** 抓帧进行中；抓帧线程写，界面线程读。 */
    @Volatile
    private var backgroundCaptureInFlight = false

    /** 每次抓帧的令牌；界面线程递增，抓帧线程据此丢弃超时或跨周期的结果。 */
    @Volatile
    private var backgroundCaptureToken = 0

    /** 本后台周期是否已尝试抓帧；只在界面线程读写。 */
    private var backgroundCaptureAttempted = false

    /** 本后台周期的抓帧任务；同一周期重复触发时复用。 */
    private var backgroundCaptureJob: Job? = null

    /** 缓存帧对应的 Surface 代数；只接受更晚代数的出帧通知。 */
    @Volatile
    private var capturedSurfaceGeneration = -1

    /** 暂停态回前台后的抓帧确认；确认到新帧或撤帧时取消。 */
    private var backgroundFrameProbeJob: Job? = null

    /** 播放失败事件，供界面提示“播放失败”。 */
    private val _playbackFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playbackFailures: SharedFlow<Unit> = _playbackFailures.asSharedFlow()

    private val _decodeMode = MutableStateFlow(DecodeMode.Hardware)
    val decodeMode: StateFlow<DecodeMode> = _decodeMode.asStateFlow()

    private val _loopMode = MutableStateFlow(LoopMode.Sequential)
    val loopMode: StateFlow<LoopMode> = _loopMode.asStateFlow()

    /** "自动播放下一个"：关时顺序播放与列表循环播完即停，单集循环不受影响。 */
    private val _autoPlayNext = MutableStateFlow(true)
    val autoPlayNext: StateFlow<Boolean> = _autoPlayNext.asStateFlow()

    /**
     * 当前画面的宽高比（宽/高）；未知时为 null。
     *
     * 引擎上报正尺寸后保持到下一次上报或本次会话结束：换集途中旧值不会先归零再回来，
     * 避免播放中来回旋转（ADR-0013）。
     */
    private val _videoAspectRatio = MutableStateFlow<Double?>(null)
    val videoAspectRatio: StateFlow<Double?> = _videoAspectRatio.asStateFlow()

    /** 起播位置设置，默认播放上次位置。 */
    private val _resumeMode = MutableStateFlow(ResumeMode.LastPosition)
    val resumeMode: StateFlow<ResumeMode> = _resumeMode.asStateFlow()

    /** 当前媒体的内嵌字幕轨道，媒体加载完成后刷新。 */
    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    /** 与当前媒体匹配的外挂字幕，按得分从高到低；已加载进轨道列表，起播不自动显示。 */
    private val _externalSubtitles = MutableStateFlow<List<MediaFile>>(emptyList())
    val externalSubtitles: StateFlow<List<MediaFile>> = _externalSubtitles.asStateFlow()

    /** 生效中的字幕；默认按起播规则定。 */
    private val _subtitleSelection = MutableStateFlow<SubtitleSelection>(SubtitleSelection.Off)
    val subtitleSelection: StateFlow<SubtitleSelection> = _subtitleSelection.asStateFlow()

    /** 定时暂停的会话状态；剩余时间不对外提供。 */
    private val _sleepTimerState = MutableStateFlow(SleepTimerState.Off)
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    /** 当前定时选项，供播放页标出勾选；未设定时或到点后为空。 */
    private val _sleepTimerOption = MutableStateFlow<SleepTimerOption?>(null)
    val sleepTimerOption: StateFlow<SleepTimerOption?> = _sleepTimerOption.asStateFlow()

    private val byNaturalName = Comparator<MediaFile> { left, right -> compareNatural(left.name, right.name) }

    /** 会话里是否有已打开的媒体。 */
    private var active = false

    /** 下发给引擎的暂停意图；媒体加载完成时据此上报状态。 */
    private var paused = false

    /** 当前媒体的位置与时长，来自引擎回调，落库时用。 */
    private var playedPositionMillis = 0L
    private var playedDurationMillis = 0L

    /** 本次会话当前目录的原始条目，供外挂字幕匹配。 */
    private var directoryEntries: List<MediaFile> = emptyList()

    /** 上一次落库时的位置；间隔不足 [SAVE_INTERVAL_MILLIS] 的更新不写库。 */
    private var savedPositionMillis = 0L

    /** 已查到、待应用的续播位置；媒体加载完成后跳一次。 */
    private var resumePositionMillis: Long? = null

    /** 当前媒体是否已播到结尾；看过一遍就不再落记录。 */
    private var watchedToEnd = false

    /** 到点暂停的等待任务；没有倒计时时为 null。 */
    private var sleepTimerJob: Job? = null

    /** 播放已停在当前文件的末尾：再按播放时从头重播当前文件。 */
    private var pausedAtEpisodeEnd = false

    /** 当前媒体是否已打开完成。 */
    private var mediaLoaded = false

    /** 本次加载所处的阶段；[LoadPhase.None] 表示没有进行中的加载。 */
    private var loadPhase = LoadPhase.None

    /** 正在加载的目标下标；起播与切集共用。 */
    private var loadTargetIndex = NO_MEDIA

    /** 正在加载的目标位置；ready 回调据此丢弃取消/恢复后迟到的目标事件。 */
    private var loadTargetUrl: String? = null

    /** 当前加载的代际序号；与引擎回调携带的序号不一致即视为迟到事件。 */
    private var expectedLoadToken = 0

    /** 切集前原视频的下标与位置，失败或取消时回到这里。 */
    private var switchOriginIndex = NO_MEDIA
    private var switchOriginPositionMillis = 0L
    private var switchOriginDurationMillis = 0L

    /** 本次切集是否由自然播完触发；片尾恢复以它为权威判据。 */
    private var switchOriginEnded = false

    /** 原视频停在片尾：恢复时没有可等待的首帧，以文件加载完成作为就绪。 */
    private var restoreAtEndOfEpisode = false

    /** 本次切集已经报过“播放失败”；恢复再失败时不重复提示。 */
    private var switchFailureReported = false

    /** 用户在片尾恢复期间按了播放：恢复完成后从头重开，不在片尾 resume。 */
    private var restartFromStartAfterRestore = false

    /** 缓冲指示的延迟任务；加载结束时取消。 */
    private var bufferingJob: Job? = null

    /** 已收到暂停态就绪信号，正在等新视频帧真正输出到 Surface。 */
    private var awaitingPresentedFrame = false

    /** Surface 重挂后等待新帧的一次性刷新；收到帧或超时后结束。 */
    private var surfaceRefreshJob: Job? = null
    private var surfaceRefreshAttempted = false

    /** 本轮加载的目标位置（续播 seek 或恢复起点）；就绪时用它初始化进度，避免界面停在 0:00。 */
    private var loadStartPositionMillis = 0L

    /** 加载期间引擎上报过的目标时长；就绪时用它初始化进度，避免拖动条被钳到最右。 */
    private var loadDurationMillis = 0L

    /** 加载中发出过续播 seek；ready 要等 seek 之后的画面，不能沿用 seek 前信号。 */
    private var seekPendingForReady = false

    /** 续播 seek 的序号；ready 的序号不小于它才算 seek 之后的信号。 */
    private var requiredSeekSerial = 0

    /** 续播位置是否已查明（或本次从开始位置播放，无需查询）；未查明前不能判定就绪。 */
    private var resumeLookupDone = true

    /**
     * 查询续播位置期间收到的首帧信号。查到位置就作废（等 seek 后的新信号）；
     * 没查到位置则补用它，避免首帧先到、查询又没有记录时白等到看门狗。
     */
    private var deferredReadyNeedsFrame: Boolean? = null

    /** 每次开始加载、恢复或清空都递增；迟到的续播查询不能作用到新一轮加载。 */
    private var loadGeneration = 0

    /** 加载完全无事件的看门狗；任一加载事件都会重置它。 */
    private var loadWatchdogJob: Job? = null

    init {
        engine.setListener(if (marshalCallbacks) MarshallingListener(this, scope) else this)
    }

    /**
     * 以 [files] 为播放列表起播 [current]；列表只收媒体文件，按名称自然排序。
     * [directoryEntries] 是当前目录的原始条目（含被媒体列表过滤掉的外挂字幕），用于外挂字幕匹配。
     * [current] 不在列表里时不动作。
     */
    fun openPlaylist(files: List<MediaFile>, current: MediaFile, directoryEntries: List<MediaFile> = emptyList()) {
        val playlist = files.filterNot { it.isDirectory }.sortedWith(byNaturalName)
        val index = playlist.indexOfFirst { it.location == current.location }
        if (index < 0) return
        _playlist.value = playlist
        this.directoryEntries = directoryEntries
        playAt(index)
    }

    /** 切换到播放列表第 [index] 项；超出范围、首次加载、切集或恢复期间不动作。 */
    fun playAt(index: Int) {
        // 切集缓冲期间只允许上一集/下一集；首次加载期间只允许返回。
        if (loadPhase != LoadPhase.None) return
        playIndex(index)
    }

    private fun playIndex(index: Int, originAtEnd: Boolean = false) {
        val media = _playlist.value.getOrNull(index) ?: return
        if (active) {
            startSwitch(index, media, originAtEnd)
        } else {
            startSession(index, media)
        }
    }

    /** 按媒体位置切换，不把列表视图的下标当作播放下标。 */
    fun play(media: MediaFile) {
        val index = _playlist.value.indexOfFirst { it.location == media.location }
        if (index >= 0) playAt(index)
    }

    /** 名称自然排序下的下一集；列表末尾时不动作。切集缓冲期间实时前移目标。 */
    fun next() {
        playAdjacent(_currentIndex.value + 1)
    }

    /** 名称自然排序下的上一集；列表开头时不动作。切集缓冲期间实时前移目标。 */
    fun previous() {
        playAdjacent(_currentIndex.value - 1)
    }

    private fun playAdjacent(index: Int) {
        when (loadPhase) {
            // 目标回到原集时直接取消切集，回原位置恢复，不再重新加载同一集。
            LoadPhase.Switching -> if (index == switchOriginIndex) cancelSwitch() else retargetSwitch(index)
            // 首次加载缓冲期间只允许返回，上一集/下一集不生效。
            LoadPhase.Initial -> Unit
            // 恢复阶段再次选择相邻集：以原视频为起点开始新的切集。
            else -> playIndex(index)
        }
    }

    /**
     * 取消进行中的切集并回到原视频：恢复原位置并保持暂停。
     * 返回是否确实取消了切集；没有切集时返回 false。
     */
    fun cancelSwitch(): Boolean {
        if (loadPhase != LoadPhase.Switching) return false
        diagnostics.log { "切集取消: ${_playlist.value.getOrNull(switchOriginIndex)?.name ?: ""}" }
        restoreOrigin()
        return true
    }

    /**
     * 界面在 Surface 挂上或分离时上报；没有画面时加载不再等抓帧。
     * Surface 重新可用时补一次就绪检查，确保后台完成的加载不会被重新拉回等待。
     */
    fun setRenderSurfaceAvailable(available: Boolean) {
        val wasAvailable = _renderSurfaceAvailable.value
        _renderSurfaceAvailable.value = available
        if (available) {
            // 新的前后台周期开始：作废尚未完成的抓帧，并允许下一次退后台重新抓。
            backgroundCaptureToken++
            backgroundCaptureAttempted = false
            backgroundCaptureJob = null
            if (!wasAvailable) beginSurfaceRefresh()
        } else {
            cancelSurfaceRefresh()
            surfaceRefreshAttempted = false
        }
        finishLoadWithoutSurface()
        evaluateDeferredReady()
    }

    /**
     * Surface 重挂后等新帧；超时仍没有帧就触发一次非 seek 刷新。
     * 收到任何出帧信号都会取消这次等待。
     */
    private fun beginSurfaceRefresh() {
        cancelSurfaceRefresh()
        surfaceRefreshAttempted = false
        if (!active) return
        val generation = engine.surfaceGeneration
        surfaceRefreshJob = scope.launch {
            delay(SURFACE_REFRESH_DELAY_MILLIS)
            if (!active) return@launch
            if (!_renderSurfaceAvailable.value) return@launch
            if (generation != engine.surfaceGeneration) return@launch
            if (surfaceRefreshAttempted) return@launch
            surfaceRefreshAttempted = true
            engine.refreshSurface()
        }
    }

    private fun cancelSurfaceRefresh() {
        surfaceRefreshJob?.cancel()
        surfaceRefreshJob = null
    }

    /**
     * 界面在退到后台、分离 Surface 之前调用：暂停态先抓一帧备用。
     * 抓帧在后台调度执行，立即返回任务；调用方只做有界等待，超时后丢弃结果。
     * 播放态不截帧，回前台由播放核心继续输出。
     */
    fun beginBackgroundCapture(): Job? {
        // 同一后台周期只尝试一次；重复调用复用同一任务。
        if (backgroundCaptureAttempted) return backgroundCaptureJob
        backgroundCaptureAttempted = true
        backgroundCaptureJob = null
        if (backgroundCaptureInFlight) return null
        // 新周期不复用旧帧，避免上一次后台留下的画面跨周期出现。
        replaceBackgroundRestoreFrame(null)
        if (!active || !paused || loadPhase != LoadPhase.None || _holdBlack.value) return null
        val generation = engine.surfaceGeneration
        val token = ++backgroundCaptureToken
        backgroundCaptureInFlight = true
        val job = scope.launch(frameProbeDispatcher) {
            val frame = runCatching { engine.grabFrame() }.getOrNull()?.bitmap
            backgroundCaptureInFlight = false
            if (frame == null || token != backgroundCaptureToken) return@launch
            capturedSurfaceGeneration = generation
            _backgroundRestoreFrame.value = frame
        }
        backgroundCaptureJob = job
        return job
    }

    /** 放弃本周期抓帧：之后到达的结果一律丢弃。 */
    fun abandonBackgroundCapture() {
        backgroundCaptureToken++
    }

    /**
     * 后台策略触发的暂停：保留片尾黑底，退后台抓帧据此跳过末帧。
     * 用户主动暂停仍走 [pause]。
     */
    fun pauseForBackground() {
        if (!active) return
        paused = true
        engine.pause()
    }

    /** 新起播一个会话：没有旧媒体要保留，画面从黑屏开始。 */
    private fun startSession(index: Int, media: MediaFile) {
        diagnostics.log { "加载开始: ${media.name}" }
        _currentIndex.value = index
        _progress.value = PlaybackProgress()
        _seekPreviewMillis.value = null
        _subtitleTracks.value = emptyList()
        _externalSubtitles.value = emptyList()
        _subtitleSelection.value = SubtitleSelection.Off
        active = true
        paused = false
        playedPositionMillis = 0
        playedDurationMillis = 0
        savedPositionMillis = 0
        resumePositionMillis = null
        watchedToEnd = false
        mediaLoaded = false
        pausedAtEpisodeEnd = false
        restoreAtEndOfEpisode = false
        switchOriginEnded = false
        switchFailureReported = false
        _holdBlack.value = false
        loadTargetIndex = index
        loadTargetUrl = media.playbackLocation
        loadPhase = LoadPhase.Initial
        beginLoadTracking()
        _switching.value = false
        _restoring.value = false
        restartFromStartAfterRestore = false
        replaceFreezeFrame(null)
        resetBackgroundCapture()
        updateState(PlaybackState.Loading)
        beginBufferingIndicator()
        beginLoadWatchdog()
        expectedLoadToken = engine.start(media.playbackLocation, paused = false)
        engine.fadeInAudio(FADE_IN_MILLIS)
        lookUpResumePosition(index, media)
    }

    /**
     * 切集：立即暂停原视频保留当前画面，再加载目标；目标画面就绪后替换。
     * 进度保持在原视频上，直到目标就绪。
     */
    private fun startSwitch(index: Int, media: MediaFile, originAtEnd: Boolean = false) {
        diagnostics.log { "切集开始: ${media.name}" }
        switchOriginIndex = _currentIndex.value
        switchOriginPositionMillis = playedPositionMillis
        switchOriginDurationMillis = playedDurationMillis
        switchOriginEnded = originAtEnd
        switchFailureReported = false
        _holdBlack.value = false
        resetBackgroundCapture()
        applySwitchTarget(index, media)
        beginBufferingIndicator()
        beginLoadWatchdog()
        // 先停住原视频，再取当前帧，最后才替换文件：抓帧必须早于 loadfile。
        engine.pause()
        replaceFreezeFrame(engine.grabFrame()?.bitmap)
        expectedLoadToken = engine.start(media.playbackLocation, paused = true)
        engine.fadeInAudio(FADE_IN_MILLIS)
        lookUpResumePosition(index, media)
    }

    /** 切集目标切换时共用的状态重置；抓帧与缓冲计时由调用方决定。 */
    private fun applySwitchTarget(index: Int, media: MediaFile) {
        _currentIndex.value = index
        loadTargetIndex = index
        loadTargetUrl = media.playbackLocation
        loadPhase = LoadPhase.Switching
        beginLoadTracking()
        _switching.value = true
        _restoring.value = false
        restartFromStartAfterRestore = false
        _seekPreviewMillis.value = null
        _subtitleTracks.value = emptyList()
        _externalSubtitles.value = emptyList()
        _subtitleSelection.value = SubtitleSelection.Off
        resumePositionMillis = null
        watchedToEnd = false
        mediaLoaded = false
        pausedAtEpisodeEnd = false
        savedPositionMillis = 0
        restoreAtEndOfEpisode = false
        // 手动切集完成即自动播放，即使切集前处于暂停；失败恢复会重新置为暂停。
        paused = false
        updateState(PlaybackState.Loading)
    }

    /**
     * 切集缓冲期间再次选择上一集/下一集：只把目标前移，直接加载最终目标。
     * 冻结画面与缓冲计时沿用第一次切集，不重新抓帧。
     */
    private fun retargetSwitch(index: Int) {
        val media = _playlist.value.getOrNull(index) ?: return
        diagnostics.log { "切集目标变更: ${media.name}" }
        applySwitchTarget(index, media)
        beginLoadWatchdog()
        expectedLoadToken = engine.start(media.playbackLocation, paused = true)
        engine.fadeInAudio(FADE_IN_MILLIS)
        lookUpResumePosition(index, media)
    }

    fun pause() {
        if (!active) return
        clearRestoreHoldOnUserAction()
        paused = true
        engine.pause()
    }

    /**
     * 继续播放；播放位置停在文件末尾时从头重播当前文件，否则接在暂停处继续。
     */
    fun resume() {
        // 播完或失败后按播放：重开当前文件，和屏上播放键一致。
        if (_state.value == PlaybackState.Ended) {
            if (_currentIndex.value != NO_MEDIA) playAt(_currentIndex.value)
            return
        }
        if (!active) return
        clearRestoreHoldOnUserAction()
        paused = false
        // 切集加载中的继续只记为意图，等目标文件就绪再出声。
        if (loadPhase == LoadPhase.Switching) return
        if (loadPhase == LoadPhase.Restoring) {
            // 片尾恢复没有可续播位置：文件重新打开后从头开始，不在片尾立即再次 EOF。
            if (restoreAtEndOfEpisode) restartFromStartAfterRestore = true
            return
        }
        if (stoppedAtEndOfEpisode()) {
            // 从头重开当前文件，避免在片尾 resume 后立刻再次结束。
            playAt(_currentIndex.value)
            return
        }
        engine.resume()
    }

    /** 音频输出中断（耳机拔出、蓝牙断连）：立即暂停，不自动恢复，等用户手动继续。 */
    fun handleAudioOutputLost() {
        if (!active) return
        paused = true
        engine.pause()
        updateState(PlaybackState.Paused)
    }

    fun togglePlayPause() {
        when (_state.value) {
            PlaybackState.Playing -> pause()
            PlaybackState.Paused -> resume()
            // 播完或失败后引擎已卸载，播放键重开当前文件。
            PlaybackState.Ended -> if (_currentIndex.value != NO_MEDIA) playAt(_currentIndex.value)
            else -> Unit
        }
    }

    fun stop() {
        val indexToSave = if (loadPhase == LoadPhase.None) _currentIndex.value else switchOriginIndex
        // 片尾恢复的原片已经看完，退出时不再写回片尾附近的进度。
        val skipEndRestoreSave = loadPhase == LoadPhase.Restoring && restoreAtEndOfEpisode
        if (active && !skipEndRestoreSave) {
            _playlist.value.getOrNull(indexToSave)?.let { savePosition(media = it, force = true) }
        }
        active = false
        paused = false
        engine.stop()
        updateState(PlaybackState.Idle)
        _seekPreviewMillis.value = null
        _decodeMode.value = DecodeMode.Hardware
        _loopMode.value = LoopMode.Sequential
        _videoAspectRatio.value = null
        _subtitleTracks.value = emptyList()
        _externalSubtitles.value = emptyList()
        _subtitleSelection.value = SubtitleSelection.Off
        directoryEntries = emptyList()
        pausedAtEpisodeEnd = false
        resetLoadState()
        cancelSleepTimer()
    }

    /** 设定定时暂停：替换已有的定时，不叠加。 */
    fun startSleepTimer(option: SleepTimerOption) {
        cancelSleepTimer()
        _sleepTimerOption.value = option
        when (option) {
            SleepTimerOption.EndOfEpisode -> _sleepTimerState.value = SleepTimerState.EndOfEpisode
            is SleepTimerOption.AfterDuration -> {
                _sleepTimerState.value = SleepTimerState.Countdown
                sleepTimerJob = scope.launch {
                    delay(option.minutes * MINUTE_MILLIS)
                    pauseOnSleepTimerElapsed()
                }
            }
        }
    }

    /** 取消定时暂停；没设定时或已经到点时也回到关。 */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.Off
        _sleepTimerOption.value = null
    }

    /** 拖动中的预览：只更新气泡位置，实际播放位置不动。 */
    fun updateSeekPreview(positionMillis: Long) {
        if (!active) return
        _seekPreviewMillis.value = positionMillis.coerceAtLeast(0)
    }

    /** 松手：跳到预览位置并收起气泡；没有预览时不动作。 */
    fun commitSeekPreview() {
        val target = _seekPreviewMillis.value ?: return
        _seekPreviewMillis.value = null
        if (!active) return
        clearRestoreHoldOnUserAction()
        // 用户主动跳转后不再算停在片尾。
        pausedAtEpisodeEnd = false
        engine.seekTo(target)
        engine.fadeInAudio(FADE_IN_MILLIS)
    }

    fun setDecodeMode(mode: DecodeMode) {
        _decodeMode.value = mode
        engine.setDecodeMode(mode)
    }

    fun toggleDecodeMode() {
        setDecodeMode(if (_decodeMode.value == DecodeMode.Hardware) DecodeMode.Software else DecodeMode.Hardware)
    }

    fun setLoopMode(mode: LoopMode) {
        _loopMode.value = mode
    }

    /** 按顺序播放 → 列表循环 → 单集循环轮换。 */
    fun cycleLoopMode() {
        setLoopMode(
            when (_loopMode.value) {
                LoopMode.Sequential -> LoopMode.ListLoop
                LoopMode.ListLoop -> LoopMode.SingleLoop
                LoopMode.SingleLoop -> LoopMode.Sequential
            },
        )
    }

    fun setAutoPlayNext(enabled: Boolean) {
        _autoPlayNext.value = enabled
    }

    fun setResumeMode(mode: ResumeMode) {
        _resumeMode.value = mode
    }

    /** 显示内嵌字幕轨道 [trackId]；外挂字幕随之停显。 */
    fun selectEmbeddedSubtitle(trackId: Int) {
        if (!active) return
        applySubtitleSelection(SubtitleSelection.Embedded(trackId))
    }

    /** 显示外挂字幕 [location]；内嵌字幕随之停显。 */
    fun selectExternalSubtitle(location: String) {
        if (!active) return
        applySubtitleSelection(SubtitleSelection.External(location))
    }

    /** 关闭字幕显示。 */
    fun disableSubtitles() {
        if (!active) return
        applySubtitleSelection(SubtitleSelection.Off)
    }

    override fun onMediaLoaded(targetUrl: String?, loadToken: Int) {
        if (!active) return
        // 被放弃目标或恢复后迟到的 FILE_LOADED 不能作用于当前加载。
        if (loadToken != expectedLoadToken) return
        if (targetUrl != null && targetUrl != loadTargetUrl) return
        mediaLoaded = true
        when (loadPhase) {
            LoadPhase.Initial -> {
                applyResumePosition()
                applyStartSubtitles(loadTargetIndex)
                finishLoadWithoutSurface()
            }
            LoadPhase.Switching -> {
                applyResumePosition()
                applyStartSubtitles(loadTargetIndex)
                finishLoadWithoutSurface()
            }
            LoadPhase.Restoring -> {
                applyStartSubtitles(_currentIndex.value)
                // 从片尾恢复没有可等待的首帧：文件打开即就绪，保持黑底暂停。
                if (restoreAtEndOfEpisode) {
                    finishReady(LoadPhase.Restoring)
                } else {
                    finishLoadWithoutSurface()
                }
            }
            LoadPhase.None -> Unit
        }
    }

    /**
     * 首帧可显示后才算就绪。播放中加载以 PLAYBACK_RESTART 为准；
     * 暂停态只有 VIDEO_RECONFIG，它可能早于首帧上屏，必须抓帧确认才算数。
     * 续播位置未查明时不能判定就绪；发出续播 seek 后只认序号不早于该 seek 的信号。
     * 真正就绪时才停掉缓冲计时并撤冻结层，探测失败不设独立短超时。
     */
    override fun onFirstFrameReady(
        needsFrameConfirmation: Boolean,
        targetUrl: String?,
        seekSerial: Int,
        loadToken: Int,
    ) {
        if (!active || !mediaLoaded) return
        val phase = loadPhase
        if (phase == LoadPhase.None) return
        // 同一 URL 重载时只有当前代际的信号有效。
        if (loadToken != expectedLoadToken) return
        // 取消或恢复之后，目标文件的迟到 ready 不能结束当前阶段。
        if (targetUrl != null && targetUrl != loadTargetUrl) return
        if (!resumeLookupDone) {
            // 期间若已收到无需确认的首帧，它是更强的信号，不能被后续重配覆盖。
            if (deferredReadyNeedsFrame != false) {
                deferredReadyNeedsFrame = needsFrameConfirmation
            }
            return
        }
        if (seekPendingForReady) {
            if (seekSerial < requiredSeekSerial) return
            seekPendingForReady = false
        }
        handleReadySignal(phase, needsFrameConfirmation)
    }

    /** 就绪判定：无需确认的首帧可直接算数；其余等新视频帧真正输出到 Surface。 */
    private fun handleReadySignal(phase: LoadPhase, needsFrameConfirmation: Boolean) {
        if (!needsFrameConfirmation) {
            finishReady(phase)
            return
        }
        if (!_renderSurfaceAvailable.value) {
            // 后台没有可渲染画面：文件已加载即视为就绪，回前台由 Surface 重挂恢复画面。
            finishReady(phase)
            return
        }
        // 暂停态信号不能证明画面已上屏；冻结画面保留到 Surface 真正出帧。
        awaitingPresentedFrame = true
    }

    /** 后台没有可渲染画面时，任何阶段的加载都以文件加载完成作为就绪。 */
    private fun finishLoadWithoutSurface() {
        val phase = loadPhase
        if (phase == LoadPhase.None) return
        if (_renderSurfaceAvailable.value) return
        if (!mediaLoaded || !resumeLookupDone) return
        finishReady(phase)
    }

    /** 条件满足时补处理续播查询期间暂存的首帧信号。 */
    private fun evaluateDeferredReady() {
        val deferred = deferredReadyNeedsFrame ?: return
        val phase = loadPhase
        if (phase == LoadPhase.None) return
        if (!mediaLoaded || !resumeLookupDone || seekPendingForReady) return
        deferredReadyNeedsFrame = null
        handleReadySignal(phase, deferred)
    }

    private fun finishReady(phase: LoadPhase) {
        awaitingPresentedFrame = false
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
        when (phase) {
            LoadPhase.Initial -> finishInitialLoad()
            LoadPhase.Switching -> finishSwitch()
            LoadPhase.Restoring -> finishRestore()
            LoadPhase.None -> Unit
        }
    }

    /** 加载相关事件到达：重置“完全无事件”看门狗。 */
    override fun onLoadActivity() {
        if (loadPhase != LoadPhase.None) beginLoadWatchdog()
    }

    /** 新视频帧已真正输出到 Surface：补上等待中的切集/恢复就绪，并撤掉后台缓存帧。 */
    override fun onFramePresented(surfaceGeneration: Int) {
        cancelSurfaceRefresh()
        clearBackgroundRestoreFrameIfCurrent(surfaceGeneration)
        if (awaitingPresentedFrame) {
            awaitingPresentedFrame = false
            finishReady(loadPhase)
        }
    }

    /** 暂停态视频输出重配：抓帧确认后再撤帧，避免重配早于首帧上屏时露黑底。 */
    override fun onSurfaceReconfigured(surfaceGeneration: Int) {
        cancelSurfaceRefresh()
        if (!_renderSurfaceAvailable.value) return
        if (_backgroundRestoreFrame.value == null) return
        if (surfaceGeneration <= capturedSurfaceGeneration) return
        backgroundFrameProbeJob?.cancel()
        backgroundFrameProbeJob = scope.launch {
            var attempts = 0
            while (isActive && attempts < BACKGROUND_FRAME_PROBE_MAX_ATTEMPTS) {
                attempts++
                if (!_renderSurfaceAvailable.value) return@launch
                if (surfaceGeneration != engine.surfaceGeneration) return@launch
                val frame = withContext(frameProbeDispatcher) { engine.grabFrame() }
                if (frame != null) {
                    clearBackgroundRestoreFrameIfCurrent(surfaceGeneration)
                    return@launch
                }
                delay(FRAME_PROBE_RETRY_MILLIS)
            }
        }
    }

    /** Surface 可用且通知来自缓存帧之后的代数时才撤帧，迟到的通知不能清掉下一周期刚抓的帧。 */
    private fun clearBackgroundRestoreFrameIfCurrent(surfaceGeneration: Int) {
        if (!_renderSurfaceAvailable.value) return
        if (_backgroundRestoreFrame.value == null) return
        if (surfaceGeneration <= capturedSurfaceGeneration) return
        if (surfaceGeneration != engine.surfaceGeneration) return
        replaceBackgroundRestoreFrame(null)
    }

    override fun onPauseChanged(paused: Boolean) {
        if (!active) return
        when (loadPhase) {
            // 首次加载中的暂停只记意图，正式状态等首帧就绪后再定。
            LoadPhase.Initial -> this.paused = paused
            // 切集与恢复期间的暂停由控制层自己设置，忽略引擎回声。
            LoadPhase.Switching, LoadPhase.Restoring -> Unit
            LoadPhase.None -> {
                this.paused = paused
                updateState(if (paused) PlaybackState.Paused else PlaybackState.Playing)
            }
        }
    }

    override fun onPlaybackFailed() {
        handleLoadFailure()
    }

    override fun onPlaybackEnded() {
        if (!active) return
        // 旧文件被替换时的结束事件不能触发自动连播；只有真正在播放时才算自然播完。
        if (loadPhase != LoadPhase.None || _state.value != PlaybackState.Playing) return
        diagnostics.log { "播放结束: ${currentMedia()?.name ?: ""}" }
        if (_sleepTimerState.value == SleepTimerState.EndOfEpisode) {
            _sleepTimerState.value = SleepTimerState.Off
            _sleepTimerOption.value = null
            pausedAtEpisodeEnd = true
            updateState(PlaybackState.Paused)
            return
        }
        // 自然播完后的下一集与手动切集共用同一条切换路径。
        when {
            _loopMode.value == LoopMode.SingleLoop -> playIndex(_currentIndex.value, originAtEnd = true)
            !_autoPlayNext.value -> endSession()
            _currentIndex.value < _playlist.value.lastIndex ->
                playIndex(_currentIndex.value + 1, originAtEnd = true)
            _loopMode.value == LoopMode.ListLoop -> playIndex(0, originAtEnd = true)
            else -> endSession()
        }
    }

    /** 顺序播完且不再继续：结束会话，播放键可重开当前文件。 */
    private fun endSession() {
        active = false
        updateState(PlaybackState.Ended)
    }

    /** 用户恢复播放操作：解除片尾黑底，并允许后续失败重新提示。 */
    private fun clearRestoreHoldOnUserAction() {
        if (loadPhase != LoadPhase.None) return
        _holdBlack.value = false
        switchFailureReported = false
    }

    override fun onPositionChanged(positionMillis: Long, durationMillis: Long) {
        if (!active) return
        // 加载中的位置属于新文件，切集时不能覆盖冻结画面上的原进度；时长要留下供就绪初始化。
        if (loadPhase != LoadPhase.None) {
            if (mediaLoaded && durationMillis > 0) loadDurationMillis = durationMillis
            return
        }
        playedPositionMillis = positionMillis
        playedDurationMillis = durationMillis
        _progress.value = PlaybackProgress(positionMillis, durationMillis)
        val media = currentMedia() ?: return
        if (reachedEnd()) {
            if (watchedToEnd) return
            watchedToEnd = true
            scope.launch { progressStore.clear(media.location) }
        } else {
            savePosition(media)
        }
    }

    /** 倒计时到点：只暂停会话，不退出播放页。 */
    private fun pauseOnSleepTimerElapsed() {
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState.Off
        _sleepTimerOption.value = null
        if (!active) return
        paused = true
        engine.pause()
        updateState(PlaybackState.Paused)
    }

    /** 播放已停在当前文件的末尾：位置到结尾，或文件播完时被定时暂停。 */
    private fun stoppedAtEndOfEpisode(): Boolean =
        _state.value == PlaybackState.Paused && (pausedAtEpisodeEnd || reachedEnd())

    /** 当前媒体条目；会话没打开媒体时为 null。 */
    private fun currentMedia(): MediaFile? = _playlist.value.getOrNull(_currentIndex.value)

    /** 位置是否到了文件结尾：时长未知时不算。 */
    private fun reachedEnd(): Boolean =
        playedDurationMillis > 0 && playedPositionMillis >= playedDurationMillis - END_TOLERANCE_MILLIS

    /** 首次打开就绪：撤掉缓冲指示，按暂停意图进入播放或暂停。 */
    private fun finishInitialLoad() {
        if (loadPhase != LoadPhase.Initial) return
        diagnostics.log { "加载成功: ${_playlist.value.getOrNull(loadTargetIndex)?.name ?: ""}" }
        loadPhase = LoadPhase.None
        loadTargetIndex = _currentIndex.value
        endBufferingIndicator()
        endLoadWatchdog()
        // 暂停态续播没有后续位置事件，用加载目标位置与已知时长初始化。
        val duration = readyDuration()
        playedPositionMillis = loadStartPositionMillis
        playedDurationMillis = duration
        _progress.value = PlaybackProgress(loadStartPositionMillis, duration)
        updateState(if (paused) PlaybackState.Paused else PlaybackState.Playing)
    }

    /** 切集就绪：目标画面直接替换冻结画面，进度从新文件开始。 */
    private fun finishSwitch() {
        if (loadPhase != LoadPhase.Switching) return
        diagnostics.log { "切集成功: ${_playlist.value.getOrNull(_currentIndex.value)?.name ?: ""}" }
        loadPhase = LoadPhase.None
        loadTargetIndex = _currentIndex.value
        endBufferingIndicator()
        endLoadWatchdog()
        _switching.value = false
        switchOriginIndex = NO_MEDIA
        switchOriginEnded = false
        replaceFreezeFrame(null)
        val duration = readyDuration()
        playedPositionMillis = loadStartPositionMillis
        playedDurationMillis = duration
        _progress.value = PlaybackProgress(loadStartPositionMillis, duration)
        if (paused) {
            updateState(PlaybackState.Paused)
        } else {
            engine.resume()
            updateState(PlaybackState.Playing)
        }
    }

    /** 失败或取消切集：重新打开原视频，停在切集前的位置；冻结画面留到它重新出帧。 */
    private fun restoreOrigin() {
        val origin = _playlist.value.getOrNull(switchOriginIndex)
        if (origin == null) {
            resetLoadState()
            return
        }
        endBufferingIndicator()
        beginLoadWatchdog()
        loadGeneration++
        loadPhase = LoadPhase.Restoring
        _restoring.value = true
        restartFromStartAfterRestore = false
        loadTargetIndex = switchOriginIndex
        loadTargetUrl = origin.playbackLocation
        // 自然播完以显式标记为准；位置兜底只在抓不到末帧时补充。
        restoreAtEndOfEpisode = switchOriginEnded ||
            (
                _freezeFrame.value == null &&
                    switchOriginDurationMillis > 0 &&
                    switchOriginPositionMillis >= switchOriginDurationMillis - END_TOLERANCE_MILLIS
                )
        // 清掉被取消目标的续播 seek 与出帧等待残留。
        awaitingPresentedFrame = false
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
        requiredSeekSerial = 0
        resumeLookupDone = true
        loadStartPositionMillis = switchOriginPositionMillis
        _currentIndex.value = switchOriginIndex
        playedPositionMillis = switchOriginPositionMillis
        playedDurationMillis = switchOriginDurationMillis
        _progress.value = PlaybackProgress(switchOriginPositionMillis, switchOriginDurationMillis)
        savedPositionMillis = switchOriginPositionMillis
        resumePositionMillis = null
        mediaLoaded = false
        watchedToEnd = restoreAtEndOfEpisode
        pausedAtEpisodeEnd = restoreAtEndOfEpisode
        paused = true
        _subtitleTracks.value = emptyList()
        _externalSubtitles.value = emptyList()
        _subtitleSelection.value = SubtitleSelection.Off
        updateState(PlaybackState.Paused)
        engine.pause()
        // 从原位置直接打开，恢复后的首帧就是应显示的画面。
        expectedLoadToken = engine.start(
            origin.playbackLocation,
            paused = true,
            startPositionMillis = switchOriginPositionMillis,
        )
    }

    /** 原视频重新出帧：撤掉冻结画面；恢复期间按过播放就继续，否则保持暂停。 */
    private fun finishRestore() {
        if (loadPhase != LoadPhase.Restoring) return
        // 片尾恢复没有可续播位置：用户按过播放就从 0 重开，不能在片尾 resume。
        val restartFromStart = restartFromStartAfterRestore && restoreAtEndOfEpisode && !paused
        restartFromStartAfterRestore = false
        // 片尾恢复且没有用户要求播放时保持黑底；用户已按播放则直接继续。
        val holdBlack = restoreAtEndOfEpisode && paused
        loadPhase = LoadPhase.None
        loadTargetIndex = _currentIndex.value
        endLoadWatchdog()
        _switching.value = false
        _restoring.value = false
        switchOriginIndex = NO_MEDIA
        restoreAtEndOfEpisode = false
        switchOriginEnded = false
        // 片尾没有可显示的首帧：直到下一次用户操作或新加载前保持黑底。
        _holdBlack.value = holdBlack
        // 普通恢复成功后允许后续真实失败重新提示；黑底期间保持去重到用户操作。
        if (!holdBlack) switchFailureReported = false
        replaceFreezeFrame(null)
        if (restartFromStart) {
            // 以新的加载从 0 开始，暂停意图已在用户操作时记为播放。
            playIndex(_currentIndex.value)
            return
        }
        if (paused) {
            updateState(PlaybackState.Paused)
        } else {
            engine.resume()
            updateState(PlaybackState.Playing)
        }
    }

    /** 就绪时的时长：优先用加载期间上报的目标时长，否则保留最后一次已知的非零时长。 */
    private fun readyDuration(): Long =
        if (loadDurationMillis > 0) loadDurationMillis else playedDurationMillis

    /** 加载失败或超时：按阶段收尾，切集中则回退原视频。[fromWatchdog] 表示没有任何加载事件。 */
    private fun handleLoadFailure(fromWatchdog: Boolean = false) {
        if (!active) return
        endLoadWatchdog()
        when (loadPhase) {
            LoadPhase.Switching -> {
                diagnostics.log { "切集失败: ${_playlist.value.getOrNull(loadTargetIndex)?.name ?: ""}" }
                switchFailureReported = true
                if (fromWatchdog) engine.discardInFlightLoads()
                restoreOrigin()
                _playbackFailures.tryEmit(Unit)
            }
            LoadPhase.Initial -> {
                diagnostics.log { "加载失败: ${_playlist.value.getOrNull(loadTargetIndex)?.name ?: ""}" }
                resetLoadState()
                active = false
                paused = true
                updateState(PlaybackState.Ended)
                // 清掉引擎里的失败残留，再次打开时不会撞上旧的 END_FILE。
                engine.stop()
                _playbackFailures.tryEmit(Unit)
            }
            LoadPhase.Restoring -> {
                diagnostics.log { "恢复失败: ${_playlist.value.getOrNull(_currentIndex.value)?.name ?: ""}" }
                val reportFailure = !switchFailureReported
                resetLoadState()
                active = false
                paused = true
                updateState(PlaybackState.Ended)
                engine.stop()
                // 同一次切集只提示一次“播放失败”。
                if (reportFailure) _playbackFailures.tryEmit(Unit)
            }
            // 播放中出错：引擎已卸载，按失败结束；播放键可重开当前文件。
            LoadPhase.None -> {
                // 片尾恢复后的 EOF 不再重复提示；等用户下一次操作或新加载。
                if (switchFailureReported) return
                diagnostics.log { "播放失败: ${currentMedia()?.name ?: ""}" }
                active = false
                paused = true
                updateState(PlaybackState.Ended)
                _playbackFailures.tryEmit(Unit)
            }
        }
    }

    /** 缓冲指示延迟出现；加载在延迟内结束就不会闪一下提示。 */
    private fun beginBufferingIndicator() {
        bufferingJob?.cancel()
        _buffering.value = false
        bufferingJob = scope.launch {
            delay(BUFFER_INDICATOR_DELAY_MILLIS)
            if (loadPhase != LoadPhase.None) _buffering.value = true
        }
    }

    /** 就绪信号到达即停掉提示计时；已经显示的提示留到本轮加载结束。 */
    private fun cancelBufferingTimer() {
        bufferingJob?.cancel()
        bufferingJob = null
    }

    private fun endBufferingIndicator() {
        cancelBufferingTimer()
        _buffering.value = false
    }

    /** 只兜底“完全无加载事件”的情况；任一 FILE_LOADED/reconfig/restart/end 都会重置。 */
    private fun beginLoadWatchdog() {
        loadWatchdogJob?.cancel()
        loadWatchdogJob = scope.launch {
            delay(LOAD_WATCHDOG_MILLIS)
            if (loadPhase != LoadPhase.None) handleLoadFailure(fromWatchdog = true)
        }
    }

    private fun endLoadWatchdog() {
        loadWatchdogJob?.cancel()
        loadWatchdogJob = null
    }

    /** 换掉冻结帧；位图交给 GC，不在 Compose 可能仍绘制时手动回收。 */
    private fun replaceFreezeFrame(frame: Bitmap?) {
        _freezeFrame.value = frame
    }

    /** 新一次加载或会话开始：作废尚未完成的后台抓帧，允许下一周期重新抓。 */
    private fun resetBackgroundCapture() {
        backgroundCaptureToken++
        backgroundCaptureAttempted = false
        backgroundCaptureJob = null
        replaceBackgroundRestoreFrame(null)
    }

    /** 换掉后台恢复帧；同样交给 GC，不手动回收。撤帧时一并停掉抓帧确认。 */
    private fun replaceBackgroundRestoreFrame(frame: Bitmap?) {
        _backgroundRestoreFrame.value = frame
        if (frame == null) {
            backgroundFrameProbeJob?.cancel()
            backgroundFrameProbeJob = null
        }
    }

    /** 清掉进行中的加载与切集状态，不改变会话本身。 */
    private fun resetLoadState() {
        loadGeneration++
        loadPhase = LoadPhase.None
        endBufferingIndicator()
        endLoadWatchdog()
        awaitingPresentedFrame = false
        cancelSurfaceRefresh()
        _switching.value = false
        _restoring.value = false
        restartFromStartAfterRestore = false
        loadTargetIndex = NO_MEDIA
        loadTargetUrl = null
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
        requiredSeekSerial = 0
        resumeLookupDone = true
        switchOriginIndex = NO_MEDIA
        restoreAtEndOfEpisode = false
        switchOriginEnded = false
        expectedLoadToken = 0
        switchFailureReported = false
        _holdBlack.value = false
        replaceFreezeFrame(null)
        resetBackgroundCapture()
    }

    /** 开始一轮加载：续播位置未查明前不允许判定就绪，seek 序号从零比较。 */
    private fun beginLoadTracking() {
        loadGeneration++
        awaitingPresentedFrame = false
        loadStartPositionMillis = 0
        loadDurationMillis = 0
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
        requiredSeekSerial = 0
        resumeLookupDone = _resumeMode.value == ResumeMode.FromStart
    }

    /**
     * 按 [ResumeMode] 读记录：读到正的位置就在媒体加载后跳一次。
     * 立即开始查询，避免首帧先到时把就绪判在续播 seek 之前。
     */
    private fun lookUpResumePosition(index: Int, media: MediaFile) {
        if (_resumeMode.value == ResumeMode.FromStart) return
        val generation = loadGeneration
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val record: MediaProgress? = progressStore.find(media.location)
            if (!active || generation != loadGeneration) return@launch
            if (loadTargetIndex != index || loadTargetUrl != media.playbackLocation) return@launch
            resumePositionMillis = record?.positionMillis?.takeIf { it > 0 }
            resumeLookupDone = true
            applyResumePosition()
            val deferred = deferredReadyNeedsFrame
            deferredReadyNeedsFrame = null
            // 没有续播 seek 时，查询完成前收到的首帧仍然有效；有 seek 时等 seek 后的新信号。
            if (!seekPendingForReady && deferred != null) handleReadySignal(loadPhase, deferred)
            finishLoadWithoutSurface()
        }
    }

    /** 续播 seek 必须在就绪前发出；发出后只接受序号不早于它的 ready。 */
    private fun applyResumePosition() {
        if (!resumeLookupDone) return
        val position = resumePositionMillis ?: return
        if (!mediaLoaded) return
        resumePositionMillis = null
        savedPositionMillis = position
        loadStartPositionMillis = position
        if (loadPhase != LoadPhase.None) {
            seekPendingForReady = true
            requiredSeekSerial = engine.seekTo(position)
        } else {
            engine.seekTo(position)
        }
    }

    /** 起播字幕：匹配到的外挂一律加载进轨道列表，显示哪一路由 [subtitleStartDecision] 定。 */
    private fun applyStartSubtitles(index: Int) {
        val media = _playlist.value.getOrNull(index) ?: return
        val tracks = engine.embeddedSubtitleTracks()
        val start = subtitleStartDecision(media.name, tracks, directoryEntries)
        start.externalToLoad.forEach { engine.addExternalSubtitle(it.playbackLocation) }
        _subtitleTracks.value = tracks
        _externalSubtitles.value = start.externalToLoad
        applySubtitleSelection(start.selection)
    }

    /** 把选择交给引擎：切到另一路时前一路停显。外挂字幕的身份是条目位置，交给引擎的是它的播放位置。 */
    private fun applySubtitleSelection(selection: SubtitleSelection) {
        when (selection) {
            is SubtitleSelection.Embedded -> engine.selectEmbeddedSubtitle(selection.trackId)
            is SubtitleSelection.External -> engine.selectExternalSubtitle(playbackSubtitleLocation(selection.location))
            SubtitleSelection.Off -> engine.disableSubtitles()
        }
        _subtitleSelection.value = selection
    }

    /** 外挂字幕条目给引擎的位置；菜单里没有这个条目时用位置本身。 */
    private fun playbackSubtitleLocation(location: String): String =
        _externalSubtitles.value.firstOrNull { it.location == location }?.playbackLocation ?: location

    /** 落进度记录；[force] 为假时位置变化不足 [SAVE_INTERVAL_MILLIS] 就不写库。 */
    private fun savePosition(media: MediaFile, force: Boolean = false) {
        if (watchedToEnd || playedPositionMillis == 0L) return
        if (!force && abs(playedPositionMillis - savedPositionMillis) < SAVE_INTERVAL_MILLIS) return
        savedPositionMillis = playedPositionMillis
        val record = MediaProgress(
            location = media.location,
            positionMillis = playedPositionMillis,
            durationMillis = playedDurationMillis,
            lastModified = media.lastModified,
        )
        scope.launch { progressStore.save(record) }
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        if (!active || width <= 0 || height <= 0) return
        _videoAspectRatio.value = width.toDouble() / height
    }

    /** 更新播放状态并记录迁移；相同状态不重复写日志。 */
    private fun updateState(state: PlaybackState) {
        val previous = _state.value
        if (previous == state) return
        _state.value = state
        diagnostics.log { "播放状态: $previous -> $state" }
    }

    /** 一次加载所处的阶段；切集与首次打开共用缓冲指示，但返回行为不同。 */
    private enum class LoadPhase { None, Initial, Switching, Restoring }

    /** 把 mpv 线程的回调按顺序投递到控制层所在的线程。 */
    private class MarshallingListener(
        private val target: PlaybackEngine.Listener,
        private val scope: CoroutineScope,
    ) : PlaybackEngine.Listener {
        override fun onMediaLoaded(targetUrl: String?, loadToken: Int) {
            scope.launch { target.onMediaLoaded(targetUrl, loadToken) }
        }

        override fun onFirstFrameReady(
            needsFrameConfirmation: Boolean,
            targetUrl: String?,
            seekSerial: Int,
            loadToken: Int,
        ) {
            scope.launch {
                target.onFirstFrameReady(needsFrameConfirmation, targetUrl, seekSerial, loadToken)
            }
        }

        override fun onLoadActivity() {
            scope.launch { target.onLoadActivity() }
        }

        override fun onFramePresented(surfaceGeneration: Int) {
            scope.launch { target.onFramePresented(surfaceGeneration) }
        }

        override fun onSurfaceReconfigured(surfaceGeneration: Int) {
            scope.launch { target.onSurfaceReconfigured(surfaceGeneration) }
        }

        override fun onPauseChanged(paused: Boolean) {
            scope.launch { target.onPauseChanged(paused) }
        }

        override fun onPlaybackEnded() {
            scope.launch { target.onPlaybackEnded() }
        }

        override fun onPlaybackFailed() {
            scope.launch { target.onPlaybackFailed() }
        }

        override fun onPositionChanged(positionMillis: Long, durationMillis: Long) {
            scope.launch { target.onPositionChanged(positionMillis, durationMillis) }
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            scope.launch { target.onVideoSizeChanged(width, height) }
        }
    }

    companion object {
        /** 起播与跳转后的音频淡入时长，固定值。 */
        const val FADE_IN_MILLIS = 500L

        /** 缓冲指示的延迟：超过这个时长仍未就绪才显示提示。 */
        const val BUFFER_INDICATOR_DELAY_MILLIS = 1000L

        /** 暂停态抓帧失败后的重试间隔；没有失败次数上限，失败由无事件看门狗判定。 */
        const val FRAME_PROBE_RETRY_MILLIS = 100L

        /** Surface 重挂后等新帧的时长；仍没有帧就触发一次非 seek 刷新。 */
        const val SURFACE_REFRESH_DELAY_MILLIS = 500L

        /** 暂停态回前台后的抓帧确认上限；超过后停止抓帧并保留缓存帧。 */
        const val BACKGROUND_FRAME_PROBE_MAX_ATTEMPTS = 20

        /** 完全无加载事件时看门狗才会触发的时长；任一加载事件都会重置。 */
        const val LOAD_WATCHDOG_MILLIS = 60_000L

        private const val NO_MEDIA = -1

        /** 引擎的位置回调比落库所需的频率高得多。 */
        private const val SAVE_INTERVAL_MILLIS = 5_000L

        /** 位置与时长的差在这个范围内就算播到了结尾。 */
        private const val END_TOLERANCE_MILLIS = 1_000L

        /** 定时暂停的分钟换算。 */
        private const val MINUTE_MILLIS = 60_000L
    }
}
