package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import android.os.SystemClock
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.compareNatural
import com.cc3301.ccplayer.progress.MediaProgress
import com.cc3301.ccplayer.progress.PlaybackProgressRepository
import com.cc3301.ccplayer.settings.ResumeMode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    /** 单调时钟；测试注入虚拟时间，生产用设备开机时间。 */
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
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

    /** 最近一次落定 seek 的通知；界面据此只撤掉比它旧的进度保持值。id 只增不清。 */
    private val _seekSettlement = MutableStateFlow<SeekSettlement?>(null)
    val seekSettlement: StateFlow<SeekSettlement?> = _seekSettlement.asStateFlow()

    /** 最后一次下发的预览 seek 目标；收尾目标与它相差不足死区时复用，不重发。 */
    private var lastPreviewSeekTargetMillis: Long? = null

    /** 最后一次下发的预览 seek 身份；死区复用时落定要认的是这一跳。 */
    private var lastPreviewSeekId: Long? = null

    /** 播放核心回报的最后一条本代 seek 身份；落定只按 seekId 确认，不按目标毫秒。 */
    private var lastExecutedSeekId: Long? = null

    /** 已下发、等播放核心回报执行的收尾 seek；执行前进度停在这个目标上。 */
    private var pendingLanding: LandingSeek? = null

    /** 预览或预览落定期间吞掉的引擎暂停回声；落定后按播放意图补一次恢复。 */
    private var ignoredPauseEcho = false

    /** 预览 seek 期间音频已静音；进入预览时置位，恢复只走 [releasePreviewMute]。 */
    private var previewMuted = false

    /** 预览的兜底收尾计时；预览结束时取消。 */
    private var previewEndJob: Job? = null

    /** 正在切集：原视频暂停，画面冻结，等目标就绪后替换；恢复原片期间仍为真。 */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /** 正在恢复原片：只剩播放/暂停在切集门禁之外额外放行。 */
    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    /** 缓冲指示是否显示：加载超过 [BUFFER_INDICATOR_DELAY_MILLIS] 仍未就绪时为真。 */
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    /**
     * 屏幕上现在该显示什么：界面只读这个派生结果（有无画面、该用的比例与回前台的缓存帧），
     * 不再自行组合加载阶段、黑底与视频轨。
     *
     * 只有 [settlePresentation] 一个写点，且只在控制层线程调用；抓帧等后台结果先落到各自字段，
     * 回到控制层线程后再派生。
     */
    private val _surfacePresentation = MutableStateFlow(SurfacePresentation())
    val surfacePresentation: StateFlow<SurfacePresentation> = _surfacePresentation.asStateFlow()

    /** 屏幕上停着的是哪一帧；撤旧帧与比例切换都只认它。 */
    private var screenFrame = ScreenFrame.None

    /**
     * 本次切集的目标是否已接管视频输出（收到过重配或首帧信号）。
     * 它不撤屏上的旧帧，只在无帧兜底时区分「旧帧可能已不在屏上」与「旧帧还在屏上」。
     */
    private var switchOutputLive = false

    /** 片尾恢复就绪后保持黑底，直到播放/暂停、seek、新加载或 stop。 */
    private var holdBlack = false

    /**
     * 当前是否有可渲染的 Surface。退到后台后为假：此时抓不到帧，
     * 切集以文件加载完成作为就绪，回前台由 Surface 重挂恢复画面。
     */
    private val _renderSurfaceAvailable = MutableStateFlow(true)
    val renderSurfaceAvailable: StateFlow<Boolean> = _renderSurfaceAvailable.asStateFlow()

    /**
     * 暂停态退到后台前缓存的画面，对外只作为 [SurfacePresentation.heldFrame] 出现：
     * 回前台先盖在视频视图上，等播放核心重新输出画面后撤掉，避免恢复期间黑屏。
     */
    private val _backgroundRestoreFrame = MutableStateFlow<Bitmap?>(null)

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

    /** 切集起点屏幕上的原片比例；原片画面还在时视频区按它绘制。 */
    private var originAspectRatio: Double? = null

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

    /** 用户在本代加载内自己跳转过：尚未下发的续播位置作废，不再把画面拉回续播点。 */
    private var userSeeked = false

    /** 当前媒体是否已播到结尾；看过一遍就不再落记录。 */
    private var watchedToEnd = false

    /** 到点暂停的等待任务；没有倒计时时为 null。 */
    private var sleepTimerJob: Job? = null

    /** 播放已停在当前文件的末尾：再按播放时从头重播当前文件。 */
    private var pausedAtEpisodeEnd = false

    /** 切集目标已提前 resume，等位置前进；收尾时不再重复下发 resume。 */
    private var switchPlaybackStarted = false

    /** 切集期间排队、尚未落地的播放意图：true 暂停、false 继续、null 没有排队。 */
    private var switchPlayPauseQueued: Boolean? = null

    /** 当前媒体是否已打开完成。 */
    private var mediaLoaded = false

    /** 当前加载目标是否有视频轨；纯音频没有可显示的画面。 */
    private var targetHasVideo = true

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

    /** 切集前原片的字幕状态；失败回滚时恢复界面，不向引擎重发命令。 */
    private var switchOriginSubtitleTracks: List<SubtitleTrack> = emptyList()
    private var switchOriginExternalSubtitles: List<MediaFile> = emptyList()
    private var switchOriginSubtitleSelection: SubtitleSelection = SubtitleSelection.Off

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

    /**
     * 出帧就绪状态机。加载阶段仍由 [LoadPhase] 表示，这里只区分是否还在等新帧：
     * 收到加载完成或就绪信号后等位置前进，2 秒无前进按就绪收尾。
     */
    private enum class ReadyState {
        /** 没有等待中的新帧。 */
        Idle,
        /** 已收到加载完成或就绪信号，等新帧上屏；2 秒无位置前进按就绪收尾。 */
        AwaitingFrame,
    }

    private var readyState = ReadyState.Idle

    /** 加载就绪后的新帧等待；超时按就绪收尾，屏幕上的旧画面继续等真正出帧。 */
    private var frameWaitJob: Job? = null

    /** 切集状态已就绪但新视频帧仍未上屏；用于无帧超时回退原片。 */
    private var switchFramePending = false

    /**
     * 切集无帧失败窗口的剩余时长。从切集开始或最后一次加载事件起算，
     * 暂停与 Surface 分离只挂起、不清零；就绪收尾不顺延。
     */
    private var switchFrameRemainingMillis = 0L

    /** 无帧窗口的绝对截止时间；计时任务暂停时结算回剩余时长。 */
    private var switchFrameDeadlineMillis: Long? = null

    /** 切集无帧失败计时；暂停或 Surface 分离时取消，按当前时间结算剩余。 */
    private var switchFrameWatchdogJob: Job? = null

    /** 本次切集是否已因回前台续过一次无帧窗口；每次重新给窗口时清零，避免反复续期。 */
    private var switchFrameWindowRenewed = false

    /** 控制器自身 resume 的回声令牌；只吞掉同一令牌的 PLAYBACK_RESTART，不吞真实加载事件。 */
    private val resumeActivityGate = ResumeActivityGate()

    /** 本次 Surface 重挂后是否还在等播放核心的首个新帧；等待期间保留后台缓存帧。 */
    private var awaitingSurfaceFrame = false

    /** 挂载后等首个新帧的计时；3 秒仍无帧只翻转一次视频输出。 */
    private var surfaceRestoreJob: Job? = null

    /** 本轮加载的目标位置（续播 seek 或恢复起点）；就绪时用它初始化进度，避免界面停在 0:00。 */
    private var loadStartPositionMillis = 0L

    /** 加载期间引擎上报过的目标时长；就绪时用它初始化进度，避免拖动条被钳到最右。 */
    private var loadDurationMillis = 0L

    /** 加载中发出过续播 seek；ready 要等 seek 之后的画面，不能沿用 seek 前信号。 */
    private var seekPendingForReady = false

    /** 续播 seek 在本代加载内的序号；ready 带回的序号不小于它才算这一跳之后的信号。 */
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
        if (loadPhase != LoadPhase.None) {
            diagnostics.log { "播放列表选择未执行: ${loadPhaseLabel(loadPhase)}阶段（索引 $index）" }
            return
        }
        playIndex(index)
    }

    private fun playIndex(index: Int, originAtEnd: Boolean = false) {
        val media = _playlist.value.getOrNull(index)
        if (media == null) {
            diagnostics.log { "播放目标不存在: 索引 $index" }
            return
        }
        if (active) {
            startSwitch(index, media, originAtEnd)
        } else {
            startSession(index, media)
        }
    }

    /** 按媒体位置切换，不把列表视图的下标当作播放下标。 */
    fun play(media: MediaFile) {
        val index = _playlist.value.indexOfFirst { it.location == media.location }
        if (index < 0) {
            diagnostics.log { "播放列表不含该条目: ${media.name}" }
            return
        }
        playAt(index)
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
            LoadPhase.Initial -> diagnostics.log { "相邻集未执行: 首次加载期间只放行返回" }
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
     * 界面在 Surface 挂上或分离时上报；没有画面时加载不再等出帧。
     * Surface 重新可用时补一次就绪检查，确保后台完成的加载不会被重新拉回等待。
     */
    fun setRenderSurfaceAvailable(available: Boolean) {
        val wasAvailable = _renderSurfaceAvailable.value
        _renderSurfaceAvailable.value = available
        if (available) {
            // 新的前后台周期开始：作废尚未完成的抓帧，并允许下一次退后台重新抓。
            resetBackgroundCapture(clearFrame = false)
            if (!wasAvailable) {
                // 未进入恢复等待（无会话、加载中或片尾黑底）：缓存帧不适用，立即撤掉。
                if (!beginSurfaceRestore()) replaceBackgroundRestoreFrame(null)
                // 后台期间无帧窗口可能已经走到截止：回前台重新给一个完整窗口，
                // 不把耗尽的窗口直接判成切集失败。
                if (switchFramePending && switchFrameRemainingMillis <= 0L && !switchFrameWindowRenewed) {
                    switchFrameRemainingMillis = NO_FRAME_WINDOW_MILLIS
                    switchFrameDeadlineMillis = null
                    switchFrameWindowRenewed = true
                }
                // 只在真正回到前台时恢复计时；已可用时重复上报不能重置剩余窗口。
                armSwitchFrameWatchdog()
            }
        } else {
            if (wasAvailable) diagnostics.log("Surface 分离")
            // Surface 销毁后屏幕上没有画面：回前台按黑底等首个新帧。
            screenFrame = ScreenFrame.None
            settlePresentation()
            cancelSurfaceRestore()
            suspendSwitchFrameWatchdog()
        }
        finishLoadWithoutSurface()
        evaluateDeferredReady()
    }

    /**
     * 回前台：两种情况都作废在途抓帧，晚到的结果不能跨进前台。
     * Surface 未重挂时画面一直在，缓存帧多余，立即撤掉；已进入恢复等待时保留缓存帧，等首个新帧。
     */
    fun onReturnedToForeground() {
        if (!_renderSurfaceAvailable.value) return
        if (awaitingSurfaceFrame) {
            // 保留缓存帧等首个新帧，但在途抓帧的结果不能跨进前台。
            resetBackgroundCapture(clearFrame = false)
            return
        }
        resetBackgroundCapture()
    }

    /**
     * Surface 重挂后只等播放核心的首个新帧：不 seek、不抓帧。
     * 视频输出重建后由播放核心自行重解码；等待超时只翻转一次视频输出，不 seek、不改播放位置。
     * 返回是否真的进入恢复等待。
     */
    private fun beginSurfaceRestore(): Boolean {
        cancelSurfaceRestore()
        if (!active) return false
        // 加载仍在等新帧时沿用加载的等待，不覆盖加载的就绪状态。
        if (loadPhase != LoadPhase.None) return false
        // 片尾黑底不显示画面，不需要恢复。
        if (holdBlack) return false
        awaitingSurfaceFrame = true
        diagnostics.log("Surface 重挂: 等待新帧")
        surfaceRestoreJob = scope.launch {
            try {
                delay(SURFACE_RESTORE_WAIT_MILLIS)
                if (!active || !_renderSurfaceAvailable.value) return@launch
                if (loadPhase != LoadPhase.None || !awaitingSurfaceFrame) return@launch
                awaitingSurfaceFrame = false
                engine.flipVideoOutput()
                diagnostics.log {
                    "Surface 恢复: ${SURFACE_RESTORE_WAIT_MILLIS / 1_000} 秒无新帧，翻转视频输出"
                }
            } finally {
                val runningJob = coroutineContext[Job]
                if (surfaceRestoreJob === runningJob) surfaceRestoreJob = null
            }
        }
        return true
    }

    /** 结束本次恢复等待：迟到的翻转不再下发，旧画面留到真正出帧。 */
    private fun cancelSurfaceRestore() {
        surfaceRestoreJob?.cancel()
        surfaceRestoreJob = null
        awaitingSurfaceFrame = false
    }

    /**
     * 界面在退到后台、分离 Surface 之前调用：暂停态先抓一帧备用。
     * 抓帧在后台调度执行，立即返回任务；调用方等待任务结束后再分离 Surface。
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
        if (!active || !paused || loadPhase != LoadPhase.None || holdBlack) return null
        val token = ++backgroundCaptureToken
        backgroundCaptureInFlight = true
        val job = scope.launch(frameProbeDispatcher) {
            val frame = runCatching { engine.grabFrame() }.getOrNull()?.bitmap
            backgroundCaptureInFlight = false
            if (frame == null || token != backgroundCaptureToken) return@launch
            // 抓帧在后台线程：结果交回控制层线程落地并派生，派生结果不在后台线程碰。
            scope.launch {
                if (token != backgroundCaptureToken) return@launch
                replaceBackgroundRestoreFrame(frame)
            }
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
        // 退后台时手势不再有收尾；落定预览并恢复声音，避免静音留到后台。
        endSeekPreview(commit = true)
        suspendSwitchFrameWatchdog()
        engine.pause()
        settleStartedSwitchAsPaused()
        // 后台暂停同样按意图立刻反映：通知栏与退后台判定都读这个状态。
        if (loadPhase == LoadPhase.None) updateState(PlaybackState.Paused)
    }

    /** 已经收到目标就绪并开始播放的切集：此时暂停按“已就绪暂停”收尾。 */
    private fun settleStartedSwitchAsPaused() {
        if (loadPhase != LoadPhase.Switching || !switchPlaybackStarted) return
        finishReady(LoadPhase.Switching)
    }

    /** 新起播一个会话：没有旧媒体要保留，画面从黑屏开始。 */
    private fun startSession(index: Int, media: MediaFile) {
        diagnostics.log { "加载开始: ${media.name}" }
        _currentIndex.value = index
        _progress.value = PlaybackProgress()
        endSeekPreview(commit = false)
        _subtitleTracks.value = emptyList()
        _externalSubtitles.value = emptyList()
        _subtitleSelection.value = SubtitleSelection.Off
        active = true
        paused = false
        switchPlayPauseQueued = null
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
        holdBlack = false
        // 新的会话从黑屏开始：没有旧画面可沿用，也没有旧比例可沿用。
        screenFrame = ScreenFrame.None
        switchOutputLive = false
        originAspectRatio = null
        loadTargetIndex = index
        loadTargetUrl = media.playbackLocation
        loadPhase = LoadPhase.Initial
        beginLoadTracking()
        settlePresentation()
        _switching.value = false
        _restoring.value = false
        restartFromStartAfterRestore = false
        resetBackgroundCapture()
        updateState(PlaybackState.Loading)
        beginBufferingIndicator()
        beginLoadWatchdog()
        resetSeekForNewLoad()
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
        switchOriginSubtitleTracks = _subtitleTracks.value
        switchOriginExternalSubtitles = _externalSubtitles.value
        switchOriginSubtitleSelection = _subtitleSelection.value
        switchFailureReported = false
        // 切集起点屏幕上的画面与比例都取自派生结果：黑底期间没有画面可沿用。
        val presentation = _surfacePresentation.value
        screenFrame = if (presentation.showsPicture) ScreenFrame.Origin else ScreenFrame.None
        originAspectRatio = presentation.aspectRatio
        holdBlack = false
        // 手动切集完成即自动播放，即使切集前处于暂停；失败恢复会重新置为暂停。
        // 只在首次切集清除，切集中再前移目标时保留用户之后的暂停意图。
        paused = false
        resetBackgroundCapture()
        applySwitchTarget(index, media)
        beginBufferingIndicator()
        beginLoadWatchdog()
        // 先停住原视频再替换文件：屏幕上停住的旧帧就是切集期间的等待画面。
        engine.pause()
        startSwitchTarget()
    }

    /**
     * 唯一的派生点：把当前事实交给 [deriveSurfacePresentation]，得出屏幕该显示什么。
     * 切集起点、新帧上屏、切集就绪、失败或取消回原片、新加载开始、会话结束、Surface 重建时调用；
     * 「撤旧帧」与「比例采纳」都只在这里一起发生。
     */
    private fun settlePresentation() {
        _surfacePresentation.value = deriveSurfacePresentation(
            screenFrame = screenFrame,
            targetHasVideo = targetHasVideo,
            holdBlack = holdBlack,
            originAspectRatio = originAspectRatio,
            targetAspectRatio = _videoAspectRatio.value,
            heldFrame = _backgroundRestoreFrame.value,
        )
    }

    /** 切集目标切换时共用的状态重置；缓冲计时由调用方决定。 */
    private fun applySwitchTarget(index: Int, media: MediaFile) {
        _currentIndex.value = index
        loadTargetIndex = index
        loadTargetUrl = media.playbackLocation
        loadPhase = LoadPhase.Switching
        beginLoadTracking()
        restartSwitchFrameDeadline()
        _switching.value = true
        _restoring.value = false
        // 新目标还没接管视频输出：屏上仍只可能是切集起点的原片画面。
        switchOutputLive = false
        restartFromStartAfterRestore = false
        endSeekPreview(commit = false)
        _subtitleTracks.value = emptyList()
        _externalSubtitles.value = emptyList()
        _subtitleSelection.value = SubtitleSelection.Off
        resumePositionMillis = null
        watchedToEnd = false
        mediaLoaded = false
        pausedAtEpisodeEnd = false
        savedPositionMillis = 0
        restoreAtEndOfEpisode = false
        switchPlaybackStarted = false
        updateState(PlaybackState.Loading)
    }

    /**
     * 切集缓冲期间再次选择上一集/下一集：只把目标前移，直接加载最终目标。
     * 缓冲计时沿用第一次切集，沿用旧帧的标记按新目标重算。
     */
    private fun retargetSwitch(index: Int) {
        val media = _playlist.value.getOrNull(index) ?: return
        diagnostics.log { "切集目标变更: ${media.name}" }
        applySwitchTarget(index, media)
        beginLoadWatchdog()
        startSwitchTarget()
    }

    /**
     * 失败或取消切集后回到原片的共同状态：进度、暂停、字幕与出帧等待。
     * 只改控制层状态，不向引擎发命令。返回 null 表示原片条目已不存在。
     */
    private fun restoreOriginBaseState(): MediaFile? {
        val originIndex = switchOriginIndex
        val origin = _playlist.value.getOrNull(originIndex)
        if (origin == null) {
            resetLoadState()
            return null
        }
        endBufferingIndicator()
        endLoadWatchdog()
        beginReadyTracking()
        switchPlaybackStarted = false
        restartFromStartAfterRestore = false
        loadTargetIndex = originIndex
        loadTargetUrl = origin.playbackLocation
        _currentIndex.value = originIndex
        loadStartPositionMillis = switchOriginPositionMillis
        playedPositionMillis = switchOriginPositionMillis
        playedDurationMillis = switchOriginDurationMillis
        _progress.value = PlaybackProgress(switchOriginPositionMillis, switchOriginDurationMillis)
        savedPositionMillis = switchOriginPositionMillis
        resumePositionMillis = null
        resumeLookupDone = true
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
        requiredSeekSerial = 0
        watchedToEnd = false
        pausedAtEpisodeEnd = false
        mediaLoaded = false
        // 回退一律保持暂停：排队的继续播放意图在这里被丢弃，留一行日志。
        if (switchPlayPauseQueued == false) {
            diagnostics.log { "切集回退保持暂停: 丢弃排队的继续播放" }
        }
        switchPlayPauseQueued = null
        paused = true
        _restoring.value = false
        _subtitleTracks.value = switchOriginSubtitleTracks
        _externalSubtitles.value = switchOriginExternalSubtitles
        _subtitleSelection.value = switchOriginSubtitleSelection
        updateState(PlaybackState.Paused)
        return origin
    }

    /** 用当前目标向引擎下发 loadfile；切集与重定向共用。 */
    private fun startSwitchTarget() {
        val media = _playlist.value.getOrNull(loadTargetIndex) ?: return
        resetSeekForNewLoad()
        expectedLoadToken = engine.start(media.playbackLocation, paused = true)
        engine.fadeInAudio(FADE_IN_MILLIS)
        lookUpResumePosition(loadTargetIndex, media)
    }

    fun pause() {
        if (!active) return
        clearRestoreHoldOnUserAction()
        paused = true
        suspendSwitchFrameWatchdog()
        engine.pause()
        settleStartedSwitchAsPaused()
        // 暂停按用户意图立刻生效，不等引擎回声；加载阶段的状态由就绪流程按意图定。
        if (loadPhase == LoadPhase.None) updateState(PlaybackState.Paused)
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
        // 切集加载中的继续只记为意图；已经就绪并开始播放时补发一次 resume。
        if (loadPhase == LoadPhase.Switching) {
            if (switchPlaybackStarted) resumeEngineForSwitch()
            // 恢复只接着剩余窗口计时；已有计时任务时保留原 deadline。
            armSwitchFrameWatchdog()
            return
        }
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
        if (switchFramePending) {
            armSwitchFrameWatchdog()
            resumeEngineForSwitch()
        } else {
            engine.resume()
        }
        // 继续播放按用户意图立刻生效，不等引擎回声；加载阶段由就绪流程按意图定。
        if (loadPhase == LoadPhase.None) updateState(PlaybackState.Playing)
    }

    /** 音频输出中断（耳机拔出、蓝牙断连）：立即暂停，不自动恢复，等用户手动继续。 */
    fun handleAudioOutputLost() {
        if (!active) return
        paused = true
        suspendSwitchFrameWatchdog()
        engine.pause()
        settleStartedSwitchAsPaused()
        updateState(PlaybackState.Paused)
    }

    /**
     * 播放/暂停：切集加载中只记意图，切集就绪后由就绪流程按同一意图生效。
     * 意图取自当前暂停标记，连按两次回到原意图。
     */
    fun togglePlayPause() {
        if (loadPhase == LoadPhase.Switching) {
            val pauseIntent = !paused
            switchPlayPauseQueued = pauseIntent
            if (pauseIntent) pause() else resume()
            diagnostics.log { "切集期间播放键: 排队${if (pauseIntent) "暂停" else "继续"}" }
            return
        }
        when (_state.value) {
            PlaybackState.Playing -> pause()
            PlaybackState.Paused -> resume()
            // 播完或失败后引擎已卸载，播放键重开当前文件。
            PlaybackState.Ended -> if (_currentIndex.value != NO_MEDIA) playAt(_currentIndex.value)
            else -> diagnostics.log { "播放键未执行: 会话未就绪（${loadPhaseLabel(loadPhase)}）" }
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
        endSeekPreview(commit = false)
        engine.stop()
        updateState(PlaybackState.Idle)
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

    /**
     * 拖动中的预览：预览位置取手指当前的真实位置，[requestSeek] 只决定这一刻要不要下发 seek。
     * 首次加载、切集、恢复原片与时长未知时什么都不写，行为与现状一致。
     */
    fun updateSeekPreview(positionMillis: Long, requestSeek: Boolean) {
        if (!previewTo(positionMillis, requestSeek)) return
        // 拖动期间靠 [seekPreviewHeartbeat] 续命：按住不动不是手感事件，不能当作预览结束。
        if (previewEndJob == null) schedulePreviewEnd(PREVIEW_IDLE_TIMEOUT_MILLIS, commit = false)
    }

    /** 拖动期间的心跳：只要还在拖，兜底就不收尾。 */
    fun seekPreviewHeartbeat() {
        if (_seekPreviewMillis.value == null) return
        schedulePreviewEnd(PREVIEW_IDLE_TIMEOUT_MILLIS, commit = false)
    }

    /** 滚轮调时长的一档：在当前位置或本次预览目标上累计 [deltaMillis]，返回新的预览目标。 */
    fun updateSeekPreviewBy(deltaMillis: Long): Long? {
        if (!active) return null
        val durationMillis = playedDurationMillis
        if (durationMillis <= 0L) return null
        val baseMillis = _seekPreviewMillis.value ?: playedPositionMillis
        val target = (baseMillis + deltaMillis).coerceIn(0L, durationMillis)
        if (target == baseMillis) return null
        if (!previewTo(target, requestSeek = true)) return null
        // 滚轮停滚由控制器收尾：窗口内再来一档就顺延，不先落定再重新静音。
        schedulePreviewEnd(WHEEL_SETTLE_MILLIS, commit = true)
        return target
    }

    /** 松手或取消拖动：落定到预览位置，声音在这一跳被执行后恢复并淡入；没有预览时不动作。 */
    fun commitSeekPreview() {
        endSeekPreview(commit = true)
    }

    /**
     * 立即跳转到 [positionMillis]：与松手收尾同一条路径，声音在这一跳被执行后恢复并淡入。
     * 目标文件还没打开时（首次加载与恢复原片的加载期）不下发，避免 seek 打到旧文件上；
     * 切集期照常下发，预览与松手收尾仍各受自己的加载期闸门约束。
     */
    fun seekTo(positionMillis: Long) {
        if (!active) return
        if (!mediaLoaded && loadPhase != LoadPhase.Switching) return
        // 用户自己跳转后，尚未下发的续播位置作废，不再把画面拉回续播点。
        userSeeked = true
        resumePositionMillis = null
        endSeekPreview(commit = true, commitTarget = positionMillis, external = true)
    }

    /** 预览的唯一写入入口：不允许预览时连标志都不写。返回是否进入了预览。 */
    private fun previewTo(positionMillis: Long, requestSeek: Boolean): Boolean {
        if (!active || !previewSeekAllowed()) return false
        // 新的拖动接管进度显示：上一次落定的等待作废，旧目标不再回写界面。
        clearLandingSeek()
        val target = positionMillis.coerceAtLeast(0)
        // 预览目标恒为手指真实位置：死区只拦下发，不参与目标本身。
        _seekPreviewMillis.value = target
        if (requestSeek) issuePreviewSeek(target)
        return true
    }

    /** 下发一次预览 seek；是否重发由界面按像素死区判定，队列由引擎收敛到「1 执行 + 1 待执行」。 */
    private fun issuePreviewSeek(target: Long) {
        val clamped = previewSeekTarget(target)
        acquirePreviewMute()
        lastPreviewSeekTargetMillis = clamped
        lastPreviewSeekId = engine.seekTo(clamped).seekId
    }

    /**
     * 预览下发与落定的目标都离片尾留出安全余量：落进这条带里的 seek 会把文件推到片尾，
     * 触发 eof-reached 的播完判定。余量严格大于「播完」容差，夹取结果才在播完带外。
     * 时长本身不超过余量时，余量按半程缩，保证预览仍有可拖动的区间；
     * 这种短片里任何位置都可能落在播完带内，由预览守卫兜底。
     */
    private fun previewSeekTarget(positionMillis: Long): Long {
        val durationMillis = playedDurationMillis
        if (durationMillis <= 0L) return positionMillis
        val margin = minOf(PREVIEW_END_MARGIN_MILLIS, durationMillis / 2)
        val limit = (durationMillis - margin).coerceAtLeast(0L)
        return positionMillis.coerceIn(0L, limit)
    }

    /** 兜底：预览活跃但 [settleMillis] 内没有心跳或滚轮更新时自动收尾。 */
    private fun schedulePreviewEnd(settleMillis: Long, commit: Boolean) {
        previewEndJob?.cancel()
        previewEndJob = scope.launch {
            delay(settleMillis)
            // 兜底收尾只清状态与声音，不落定、不改写与预览无关的播放状态。
            endSeekPreview(commit = commit)
        }
    }

    /**
     * 预览的唯一结束入口：收起预览位置与兜底计时。
     * [commit] 为真时把落定目标交给引擎：与最后一次下发的目标完全相同时复用，否则补一跳精确 seek；
     * 声音在播放核心回报这一跳执行完之后恢复并淡入，避免落定前的旧位置以满音量播出。
     * [commitTarget] 为外部跳转的目标，省略时落定到预览位置。
     * [external] 为真表示外部跳转：不受加载期闸门约束。
     */
    private fun endSeekPreview(commit: Boolean, commitTarget: Long? = null, external: Boolean = false) {
        previewEndJob?.cancel()
        previewEndJob = null
        // 预览落定同样不进片尾安全余量：停在片尾会立刻触发播完与自动暂停。
        val target = commitTarget ?: _seekPreviewMillis.value?.let(::previewSeekTarget)
        val lastIssuedTarget = lastPreviewSeekTargetMillis
        val lastIssuedSeekId = lastPreviewSeekId
        _seekPreviewMillis.value = null
        lastPreviewSeekTargetMillis = null
        lastPreviewSeekId = null
        if (!commit) {
            // 取消、切集或会话结束：落定作废，静音与吞掉的暂停回声都在这里收场。
            settleIgnoredPauseEcho()
            clearLandingSeek()
            releasePreviewMute()
            return
        }
        // 会话已结束：没有落定可等，静音与吞掉的暂停回声都在这里收场。
        if (!active) {
            settleIgnoredPauseEcho()
            releasePreviewMute()
            return
        }
        // 没有新的落点：等待中的落定仍靠它自己的回执放开，静音不在早退分支恢复；
        // 吞掉的暂停回声在这里收场，恢复仍按播放意图决定。
        if (target == null) {
            settleIgnoredPauseEcho()
            return
        }
        // 预览落定的加载期闸门：会推走本代的续播序号，落定等不到回执；外部跳转照常下发。
        // 吞掉的暂停回声在这里清掉，新加载由自己接管引擎。
        if (!external && loadPhase != LoadPhase.None) {
            settleIgnoredPauseEcho()
            releasePreviewMute()
            return
        }
        clearRestoreHoldOnUserAction()
        // 用户主动跳转后不再算停在片尾。
        pausedAtEpisodeEnd = false
        // 外部跳转改写这一代加载的落点：就绪时进度从它开始，不回到加载起点。
        if (external && loadPhase != LoadPhase.None) loadStartPositionMillis = target
        // 同一目标再下发一次会让画面先闪一下再落定：目标完全相同时复用最后一跳，否则落点会停在旧目标上。
        val seekId = if (lastIssuedTarget != null && lastIssuedSeekId != null && target == lastIssuedTarget) {
            lastIssuedSeekId
        } else {
            engine.seekTo(target).seekId
        }
        // 外部跳转（带目标）是普通 seek；没有外部目标时落定的是预览位置。
        landSeek(target, seekId, fromPreview = commitTarget == null)
    }

    /**
     * 登记落定：进度先停在目标上，等播放核心回报这一跳已执行后再放开。
     * 复用的那一跳此前已经回报过执行时，落定当场结算。
     */
    private fun landSeek(targetMillis: Long, seekId: Long, fromPreview: Boolean) {
        playedPositionMillis = targetMillis
        _progress.value = PlaybackProgress(targetMillis, _progress.value.durationMillis)
        pendingLanding = LandingSeek(seekId, targetMillis, fromPreview)
        if (lastExecutedSeekId == seekId) settleLanding()
    }

    /** 落定结算：恢复声音并淡入，再按进程级序号通知界面撤掉保持值。 */
    private fun settleLanding() {
        val landing = pendingLanding ?: return
        pendingLanding = null
        releasePreviewMute()
        engine.fadeInAudio(FADE_IN_MILLIS)
        // 预览期间吞掉的暂停回声在这里收尾：原本在播放就让引擎接着播。
        settleIgnoredPauseEcho()
        _seekSettlement.value = SeekSettlement(settlementIds.incrementAndGet(), landing.targetMillis)
    }

    /**
     * 吞掉的 keep-open 暂停回声在这里收场：清标志；
     * 播放意图仍为播放且没有新加载接管引擎时恢复播放。
     */
    private fun settleIgnoredPauseEcho() {
        if (!ignoredPauseEcho) return
        ignoredPauseEcho = false
        if (active && loadPhase == LoadPhase.None && !paused) engine.resume()
    }

    /** 放弃等待中的落定：新拖动、新加载或停止后不再保留；静音由调用路径统一恢复。 */
    private fun clearLandingSeek() {
        pendingLanding = null
    }

    /** 预览静音的唯一写入入口：整段拖动只静音一次，逐次 seek 各自淡入会造成断续爆音。 */
    private fun acquirePreviewMute() {
        if (previewMuted) return
        previewMuted = true
        engine.setAudioMuted(true)
    }

    /** 恢复预览静音：落定执行完、预览被取消或会话结束时都只能经由这里恢复。 */
    private fun releasePreviewMute() {
        if (!previewMuted) return
        previewMuted = false
        engine.setAudioMuted(false)
    }

    /**
     * 已下发的落定：认哪一跳 seek、界面落点（死区复用时两者可以不同），
     * 以及是否来自预览。播完判定只拦预览来源的落定。
     */
    private class LandingSeek(
        val seekId: Long,
        val targetMillis: Long,
        val fromPreview: Boolean,
    )

    /** 预览 seek 只在正常播放中下发；加载阶段与时长未知时保持原行为。 */
    private fun previewSeekAllowed(): Boolean =
        loadPhase == LoadPhase.None &&
            playedDurationMillis > 0L &&
            (_state.value == PlaybackState.Playing || _state.value == PlaybackState.Paused)

    /** 预览中，或预览来源的落定还没执行完：播完判定与引擎暂停回声都要让路。 */
    private fun previewLandingInFlight(): Boolean =
        _seekPreviewMillis.value != null || pendingLanding?.fromPreview == true

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

    override fun onMediaLoaded(targetUrl: String?, loadToken: Int, hasVideo: Boolean) {
        if (!active) return
        // 被放弃目标或恢复后迟到的 FILE_LOADED 不能作用于当前加载。
        if (loadToken != expectedLoadToken) return
        if (targetUrl != null && targetUrl != loadTargetUrl) return
        mediaLoaded = true
        targetHasVideo = hasVideo
        settlePresentation()
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
                // 已收到加载完成：即使画面信号迟迟不来，也在 2 秒后按就绪收尾。
                armSwitchFrameFallback()
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
     * 暂停态只有 VIDEO_RECONFIG，它可能早于首帧上屏，切集要等播放位置真正前进才算数。
     * 续播位置未查明时不能判定就绪；发出续播 seek 后只认序号不小于该 seek 的信号，
     * 就绪判定按加载代际与该代序号，不与 seekId 混用。
     * 真正就绪时才停掉缓冲计时；等待出帧不设独立短超时。
     */
    override fun onFirstFrameReady(
        needsFrameConfirmation: Boolean,
        targetUrl: String?,
        seekSerial: Int,
        loadToken: Int,
    ) {
        if (!active || !mediaLoaded) return
        // 同一 URL 重载时只有当前代际的信号有效；旧集的迟到回执不得抬高本代的 seek 回执基线。
        if (loadToken != expectedLoadToken) return
        // 取消或恢复之后，目标文件的迟到 ready 不能结束当前阶段。
        if (targetUrl != null && targetUrl != loadTargetUrl) return
        val phase = loadPhase
        if (phase == LoadPhase.None) return
        // 目标已接管视频输出，画面却可能还没上屏：这里不撤屏上的旧帧。
        // 撤帧只发生在确认接管的 [handleReadySignal] 强信号与 [confirmFrame]。
        switchOutputLive = true
        if (!resumeLookupDone) {
            // 期间若已收到无需确认的首帧，它是更强的信号，不能被后续重配覆盖。
            if (deferredReadyNeedsFrame != false) {
                deferredReadyNeedsFrame = needsFrameConfirmation
            }
            return
        }
        if (seekPendingForReady) {
            // 不早于续播那一跳的命令序号才算数：更早的信号不能就绪，之后的 seek 也照常接受。
            if (seekSerial < requiredSeekSerial) return
            seekPendingForReady = false
        }
        handleReadySignal(phase, needsFrameConfirmation)
    }

    /**
     * 播放核心回报一条 seek 已执行：只有本代自己下发的那一跳才算数。
     * 旧代或已被覆盖的旧跳的回执直接丢弃，既不结算，也不改写本代的执行身份。
     */
    override fun onSeekExecuted(seekId: Long, targetMillis: Long) {
        if (seekId != pendingLanding?.seekId && seekId != lastPreviewSeekId) return
        lastExecutedSeekId = seekId
        if (pendingLanding?.seekId != seekId) return
        settleLanding()
    }

    /**
     * 就绪判定：无需确认的首帧可直接算数；暂停态信号不能证明画面已上屏。
     * 切集目标此时开始播放，等位置前进或 2 秒兜底收尾；屏幕上的旧画面留到真正出帧。
     * 恢复与首次打开没有可等待的播放推进，按文件就绪收尾。
     */
    private fun handleReadySignal(phase: LoadPhase, needsFrameConfirmation: Boolean) {
        if (!needsFrameConfirmation) {
            // 首帧已可显示：屏上就是当前加载的画面，切集起点的旧帧到此撤掉、比例同时换新。
            screenFrame = ScreenFrame.Target
            settlePresentation()
            finishReady(phase)
            return
        }
        if (!_renderSurfaceAvailable.value) {
            // 后台没有可渲染画面：文件已加载即视为就绪，回前台由 Surface 重挂恢复画面。
            finishReady(phase)
            return
        }
        if (phase == LoadPhase.Switching) {
            if (paused) {
                // 切集中用户又暂停：按暂停收尾；暂停态就绪即撤旧帧，直接露新集首帧。
                finishReady(phase)
                return
            }
            // 手动切集完成后自动播放；先起播，等播放位置前进或 2 秒兜底再收尾。
            readyState = ReadyState.AwaitingFrame
            switchPlaybackStarted = true
            resumeEngineForSwitch()
            beginFrameWait()
            return
        }
        finishReady(phase)
    }

    /**
     * 切集起播后等位置前进。2 秒内等不到时按就绪收尾，不落到 60 秒看门狗；
     * 续播查询或 seek 未完成时顺延，屏幕上的旧画面不受这次收尾影响。
     */
    private fun beginFrameWait() {
        frameWaitJob?.cancel()
        frameWaitJob = scope.launch {
            try {
                while (true) {
                    delay(FRAME_FALLBACK_MILLIS)
                    if (!active || readyState != ReadyState.AwaitingFrame) return@launch
                    // 续播查询或 seek 未完成时不能收尾：顺延一轮，避免进度回跳。
                    if (!resumeLookupDone || seekPendingForReady) continue
                    diagnostics.log { "就绪兜底: ${loadLabel()} 2 秒内没有位置前进" }
                    finishReady(loadPhase)
                    return@launch
                }
            } finally {
                val runningJob = coroutineContext[Job]
                if (frameWaitJob === runningJob) frameWaitJob = null
            }
        }
    }

    /**
     * 切集目标已加载时启动 2 秒兜底。续播查询或 seek 未完成时由兜底循环顺延，
     * 不在 seek 前收尾；暂停态也启动，到点按暂停收尾。
     */
    private fun armSwitchFrameFallback() {
        if (loadPhase != LoadPhase.Switching) return
        if (!mediaLoaded || !_renderSurfaceAvailable.value) return
        // 已经在等新帧时保留原计时，续播查询完成不能把 2 秒重新起算。
        if (readyState == ReadyState.AwaitingFrame && frameWaitJob != null) return
        readyState = ReadyState.AwaitingFrame
        beginFrameWait()
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

    private fun finishReady(phase: LoadPhase, frameConfirmed: Boolean = false) {
        readyState = ReadyState.Idle
        frameWaitJob?.cancel()
        frameWaitJob = null
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
        when (phase) {
            LoadPhase.Initial -> finishInitialLoad()
            LoadPhase.Switching -> finishSwitch(frameConfirmed)
            LoadPhase.Restoring -> finishRestore()
            LoadPhase.None -> Unit
        }
    }

    /** 加载相关事件到达：重置“完全无事件”看门狗。 */
    override fun onLoadActivity(activity: LoadActivity, loadToken: Int?) {
        // 控制器自身 resume 产生的 PLAYBACK_RESTART 不是新的加载：不重置看门狗，也不刷新无帧窗口。
        if (resumeActivityGate.shouldIgnore(activity, loadToken)) return
        if (loadPhase != LoadPhase.None) beginLoadWatchdog()
        // 目标加载期间的真实加载事件都按“最后一次加载事件”刷新无帧窗口。
        if (loadPhase == LoadPhase.Switching) restartSwitchFrameDeadline()
    }

    /**
     * 新画面已真正输出到 Surface：加载就绪后播放位置前进，或 Surface 重挂后再次前进。
     * 新帧确认在这里撤掉后台缓存帧。片尾恢复的黑底由 [finishRestore] 保持或撤掉。
     */
    override fun onFramePresented(loadToken: Int) {
        if (!active) return
        confirmFrame(loadToken)
    }

    /**
     * 新帧确认：撤掉后台缓存帧，并结束等待中的就绪或 Surface 恢复。
     * 只有当前加载的帧才能撤掉这一层，迟到或旧加载的帧不能提前撤掉它。
     */
    private fun confirmFrame(loadToken: Int) {
        if (loadToken != expectedLoadToken) return
        replaceBackgroundRestoreFrame(null)
        if (awaitingSurfaceFrame) {
            cancelSurfaceRestore()
            diagnostics.log("Surface 恢复: 新帧已上屏")
        }
        // 新帧真的上屏：屏上就是当前加载的画面。撤旧帧只认这一处与就绪强信号。
        screenFrame = ScreenFrame.Target
        settlePresentation()
        if (switchFramePending) clearPendingSwitchFrame()
        if (readyState == ReadyState.AwaitingFrame) finishReady(loadPhase, frameConfirmed = true)
    }

    override fun onPauseChanged(paused: Boolean) {
        if (!active) return
        when (loadPhase) {
            // 首次加载中的暂停只记意图，正式状态等首帧就绪后再定。
            LoadPhase.Initial -> this.paused = paused
            // 切集与恢复期间的暂停由控制层自己设置，忽略引擎回声。
            LoadPhase.Switching, LoadPhase.Restoring -> Unit
            LoadPhase.None -> {
                // keep-open 播完后引擎会回一条暂停：预览与预览落定期间这不是用户意图，不能压状态。
                if (paused && previewLandingInFlight()) {
                    ignoredPauseEcho = true
                    return
                }
                this.paused = paused
                // 引擎主动暂停时挂起无帧计时；恢复后按剩余窗口继续。
                if (paused) suspendSwitchFrameWatchdog() else if (switchFramePending) armSwitchFrameWatchdog()
                updateState(if (paused) PlaybackState.Paused else PlaybackState.Playing)
            }
        }
    }

    override fun onPlaybackFailed(loadToken: Int, errorText: String?) {
        // 取消或重定向后，旧目标迟到的失败不能改变当前阶段，也不提示。
        if (loadToken != expectedLoadToken) return
        handleLoadFailure(errorText = errorText)
    }

    override fun onPlaybackEnded(loadToken: Int) {
        if (!active) return
        // 只看当前加载的自然播完标记；keep-open 自动暂停的回声不能把播完吞掉。
        if (loadToken != expectedLoadToken || loadPhase != LoadPhase.None) return
        // 片尾恢复的黑底不重复触发连播，等用户操作或新加载。
        if (holdBlack) return
        // 预览 seek 或预览来源的落定还没执行完都不算播完：切集会打断拖动，
        // 落定的 seek 会把播放带离片尾；普通 seek 落到片尾照常连播。
        if (previewLandingInFlight()) {
            diagnostics.log { "播放结束: 预览或落定中，不触发连播" }
            return
        }
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
        clearPendingSwitchFrame()
        // 会话结束：恢复播放由播放键重开，这里只收起预览、静音与吞掉的暂停回声。
        active = false
        endSeekPreview(commit = false)
        updateState(PlaybackState.Ended)
    }

    /** 用户恢复播放操作：解除片尾黑底，并允许后续失败重新提示。 */
    private fun clearRestoreHoldOnUserAction() {
        if (loadPhase != LoadPhase.None) return
        holdBlack = false
        settlePresentation()
        switchFailureReported = false
    }

    override fun onPositionChanged(positionMillis: Long, durationMillis: Long) {
        if (!active) return
        // 预览 seek 挪动的位置不是用户落定的播放位置：不推进度、不落库。
        if (_seekPreviewMillis.value != null) return
        // 落定 seek 未执行完：位置可能还是 seek 前后的旧样本，进度停在目标上不回跳。
        if (pendingLanding != null) return
        // 加载中的位置属于新文件，切集时不能覆盖原片保留的进度；时长要留下供就绪初始化。
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
        suspendSwitchFrameWatchdog()
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

    /** 切集就绪：目标画面直接替换屏幕上的旧画面，进度从新文件开始。 */
    private fun finishSwitch(frameConfirmed: Boolean) {
        if (loadPhase != LoadPhase.Switching) return
        diagnostics.log { "切集成功: ${_playlist.value.getOrNull(_currentIndex.value)?.name ?: ""}" }
        loadPhase = LoadPhase.None
        loadTargetIndex = _currentIndex.value
        // 排队的播放意图在这里落地，之后不再算排队。
        switchPlayPauseQueued = null
        endBufferingIndicator()
        endLoadWatchdog()
        _switching.value = false
        // 状态就绪但新帧还没上屏时保留原片信息，60 秒仍无帧就回退。
        switchFramePending = targetHasVideo && !frameConfirmed
        if (!switchFramePending) {
            switchOriginIndex = NO_MEDIA
            switchOriginEnded = false
        }
        // 暂停态没有更靠后的出帧信号：就绪即撤旧帧，直接露新集首帧，不铺黑底。
        // Surface 分离时屏幕上没有画面可露，按分离时的判定维持黑底。
        if (paused && _renderSurfaceAvailable.value) screenFrame = ScreenFrame.Target
        settlePresentation()
        val duration = readyDuration()
        playedPositionMillis = loadStartPositionMillis
        playedDurationMillis = duration
        _progress.value = PlaybackProgress(loadStartPositionMillis, duration)
        if (paused) {
            switchPlaybackStarted = false
            updateState(PlaybackState.Paused)
        } else {
            if (!switchPlaybackStarted) resumeEngineForSwitch()
            switchPlaybackStarted = false
            updateState(PlaybackState.Playing)
        }
        // 状态收尾不顺延无帧窗口：从切集开始算的窗口继续跑。
        if (switchFramePending) {
            armSwitchFrameWatchdog()
        } else {
            suspendSwitchFrameWatchdog()
            clearSwitchFrameDeadline()
        }
    }

    /**
     * 切集开始或收到新的加载事件：只把剩余时长重置为完整窗口并清空绝对 deadline。
     * 绝对 deadline 只能由 [armSwitchFrameWatchdog] 在可计时时建立，
     * 暂停或 Surface 分离期间不允许存在 deadline。
     */
    private fun restartSwitchFrameDeadline() {
        switchFrameRemainingMillis = NO_FRAME_WINDOW_MILLIS
        switchFrameDeadlineMillis = null
        switchFrameWindowRenewed = false
        switchFrameWatchdogJob?.cancel()
        switchFrameWatchdogJob = null
        armSwitchFrameWatchdog()
    }

    /** 控制器自身 resume：只忽略同一加载令牌的 PLAYBACK_RESTART 回声，不刷新无帧窗口。 */
    private fun resumeEngineForSwitch() {
        if (loadPhase == LoadPhase.Switching) resumeActivityGate.onResume(expectedLoadToken)
        engine.resume()
    }

    /**
     * 播放态切集等新帧。已有计时任务时 no-op，保留原 deadline；
     * 暂停或没有可渲染 Surface 时不启动，恢复时按剩余时长继续。
     */
    private fun armSwitchFrameWatchdog() {
        if (switchFrameWatchdogJob != null) return
        if (!active || paused || !_renderSurfaceAvailable.value) return
        if (loadPhase != LoadPhase.Switching && !switchFramePending) return
        val deadline = switchFrameDeadlineMillis ?: (nowMillis() + switchFrameRemainingMillis).also {
            switchFrameDeadlineMillis = it
        }
        val remaining = (deadline - nowMillis()).coerceAtLeast(0)
        if (remaining <= 0) {
            switchFrameDeadlineMillis = null
            switchFrameRemainingMillis = 0
            if (switchFramePending) onSwitchFrameMissing()
            return
        }
        switchFrameWatchdogJob = scope.launch {
            try {
                delay(remaining)
                switchFrameDeadlineMillis = null
                switchFrameRemainingMillis = 0
                if (active && switchFramePending && !paused && _renderSurfaceAvailable.value) {
                    onSwitchFrameMissing()
                }
            } finally {
                val runningJob = coroutineContext[Job]
                if (switchFrameWatchdogJob === runningJob) switchFrameWatchdogJob = null
            }
        }
    }

    /** 暂停或 Surface 分离时按当前时间结算剩余；恢复后接着跑。 */
    private fun suspendSwitchFrameWatchdog() {
        switchFrameRemainingMillis = remainingSwitchFrameMillis()
        switchFrameDeadlineMillis = null
        switchFrameWatchdogJob?.cancel()
        switchFrameWatchdogJob = null
    }

    private fun remainingSwitchFrameMillis(): Long {
        val deadline = switchFrameDeadlineMillis ?: return switchFrameRemainingMillis
        return (deadline - nowMillis()).coerceAtLeast(0)
    }

    private fun clearSwitchFrameDeadline() {
        switchFrameRemainingMillis = 0
        switchFrameDeadlineMillis = null
        switchFrameWindowRenewed = false
        resumeActivityGate.reset()
    }

    private fun onSwitchFrameMissing() {
        switchFrameWatchdogJob = null
        clearSwitchFrameDeadline()
        if (!active || !switchFramePending) return
        val origin = _playlist.value.getOrNull(switchOriginIndex)
        if (origin == null) {
            switchFramePending = false
            return
        }
        logLoadFailure("切集失败", LoadFailureSource.NoFrame, phase = LoadPhase.Switching)
        switchFailureReported = true
        switchFramePending = false
        _switching.value = true
        // 收到过目标的重配或首帧信号却迟迟等不到帧：旧帧可能已经不在屏上，按黑底回退原片。
        // 完全没有接管信号时，原片画面还停在屏上，继续沿用、不铺黑底。
        if (switchOutputLive) {
            screenFrame = ScreenFrame.None
            holdBlack = true
            settlePresentation()
        }
        restoreOrigin()
        _playbackFailures.tryEmit(Unit)
    }

    /** 真正出帧或目标没有视频轨：无帧失败窗口结束。 */
    private fun clearPendingSwitchFrame() {
        switchFrameWatchdogJob?.cancel()
        switchFrameWatchdogJob = null
        clearSwitchFrameDeadline()
        switchFramePending = false
        switchOriginIndex = NO_MEDIA
        switchOriginEnded = false
    }

    /**
     * 失败或取消切集：重新打开原视频，停在切集前的位置。
     * 屏幕上还有原片画面时继续沿用、不铺黑底；片尾自动切集按黑底等待，
     * 目标已接管视频输出却等不到帧的情形由 60 秒兜底先撤帧，恢复期同样按黑底等原片重新出帧。
     */
    private fun restoreOrigin() {
        val origin = restoreOriginBaseState() ?: return
        // 自然播完以显式标记为准；位置兜底覆盖手动切集恰好停在片尾的情况。
        restoreAtEndOfEpisode = switchOriginEnded ||
            (
                switchOriginDurationMillis > 0 &&
                    switchOriginPositionMillis >= switchOriginDurationMillis - END_TOLERANCE_MILLIS
                )
        watchedToEnd = restoreAtEndOfEpisode
        pausedAtEpisodeEnd = restoreAtEndOfEpisode
        // 片尾自动切集失败不能续播：恢复期不沿用旧帧，按黑底等原片重新出帧。
        // 无帧兜底已经挂起黑底时保持，恢复结束时再按用户意图收尾。
        holdBlack = holdBlack || (switchOriginEnded && paused)
        loadGeneration++
        loadPhase = LoadPhase.Restoring
        _restoring.value = true
        // 进入恢复阶段后再派生：恢复期的黑底与沿用旧帧按这一阶段的规则判定。
        settlePresentation()
        beginLoadWatchdog()
        engine.pause()
        // 从原位置直接打开，恢复后的首帧就是应显示的画面。
        resetSeekForNewLoad()
        expectedLoadToken = engine.start(
            origin.playbackLocation,
            paused = true,
            startPositionMillis = switchOriginPositionMillis,
        )
    }

    /** 原视频重新出帧：撤掉黑底；恢复期间按过播放就继续，否则保持暂停。 */
    private fun finishRestore() {
        if (loadPhase != LoadPhase.Restoring) return
        // 片尾恢复没有可续播位置：用户按过播放就从 0 重开，不能在片尾 resume。
        val restartFromStart = restartFromStartAfterRestore && restoreAtEndOfEpisode && !paused
        restartFromStartAfterRestore = false
        // 只有自然播完触发的自动切集才按黑底等待；手动切集的失败回退沿用屏幕上的旧帧。
        // 恢复到片尾时用户按过播放就从 0 重开，黑底随之解除。
        val blackHold = switchOriginEnded && paused
        loadPhase = LoadPhase.None
        loadTargetIndex = _currentIndex.value
        endLoadWatchdog()
        _switching.value = false
        _restoring.value = false
        switchOriginIndex = NO_MEDIA
        restoreAtEndOfEpisode = false
        switchOriginEnded = false
        switchFramePending = false
        suspendSwitchFrameWatchdog()
        clearSwitchFrameDeadline()
        // 片尾没有可显示的首帧：直到下一次用户操作或新加载前保持黑底。
        holdBlack = blackHold
        settlePresentation()
        // 普通恢复成功后允许后续真实失败重新提示；黑底期间保持去重到用户操作。
        if (!blackHold) switchFailureReported = false
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

    /**
     * 加载失败或超时：按阶段收尾，切集中则回退原视频。
     * [fromWatchdog] 表示没有任何加载事件；[errorText] 是播放核心给出的错误文本。
     */
    private fun handleLoadFailure(fromWatchdog: Boolean = false, errorText: String? = null) {
        if (!active) return
        endLoadWatchdog()
        val source = if (fromWatchdog) LoadFailureSource.Watchdog else LoadFailureSource.Core
        when (loadPhase) {
            LoadPhase.Switching -> {
                logLoadFailure("切集失败", source, errorText)
                switchFailureReported = true
                if (fromWatchdog) engine.discardInFlightLoads()
                restoreOrigin()
                _playbackFailures.tryEmit(Unit)
            }
            LoadPhase.Initial -> {
                logLoadFailure("加载失败", source, errorText)
                resetLoadState()
                active = false
                paused = true
                updateState(PlaybackState.Ended)
                // 清掉引擎里的失败残留，再次打开时不会撞上旧的 END_FILE。
                engine.stop()
                _playbackFailures.tryEmit(Unit)
            }
            LoadPhase.Restoring -> {
                logLoadFailure("恢复失败", source, errorText)
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
                logLoadFailure("播放失败", source, errorText)
                clearPendingSwitchFrame()
                // 会话就此结束：预览静音走同一出口恢复，不能把音频留在静音；
                // 吞掉的暂停回声也在结束时清掉，active 已为假，不再恢复播放。
                releasePreviewMute()
                active = false
                paused = true
                settleIgnoredPauseEcho()
                updateState(PlaybackState.Ended)
                _playbackFailures.tryEmit(Unit)
            }
        }
    }

    /** 失败来源；无帧超时与加载看门狗、播放核心主动报错分开记录。 */
    private enum class LoadFailureSource { Core, Watchdog, NoFrame }

    /** 失败日志包含目标、加载令牌、阶段与来源，便于区分看门狗与播放核心主动报错。 */
    private fun logLoadFailure(
        label: String,
        source: LoadFailureSource,
        errorText: String? = null,
        phase: LoadPhase = loadPhase,
    ) {
        val sourceText = when (source) {
            LoadFailureSource.Watchdog ->
                "看门狗超时（${LOAD_WATCHDOG_MILLIS / 1_000} 秒无加载事件）"
            LoadFailureSource.NoFrame ->
                "无帧超时（${NO_FRAME_WINDOW_MILLIS / 1_000} 秒未出帧）"
            LoadFailureSource.Core ->
                "播放核心报错（${errorText?.takeIf { it.isNotBlank() } ?: "无错误文本"}）"
        }
        diagnostics.log {
            "$label: ${loadLabel()} | 目标: ${loadTargetUrl ?: "-"} | 加载令牌: $expectedLoadToken | " +
                "阶段: ${loadPhaseLabel(phase)} | 来源: $sourceText"
        }
    }

    /** 阶段日志文案；测试断言文案而不是内部枚举名。 */
    private fun loadPhaseLabel(phase: LoadPhase): String = when (phase) {
        LoadPhase.Initial -> "首次加载"
        LoadPhase.Switching -> "切集"
        LoadPhase.Restoring -> "恢复原片"
        LoadPhase.None -> "播放"
    }

    /** 当前加载目标的可读名称；没有名称时回退下标。 */
    private fun loadLabel(): String {
        val name = _playlist.value.getOrNull(loadTargetIndex)?.name
        if (!name.isNullOrBlank()) return name
        return if (loadTargetIndex >= 0) "索引 $loadTargetIndex" else "-"
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
            if (loadPhase != LoadPhase.None) {
                diagnostics.log {
                    "加载看门狗触发: ${loadLabel()} | 目标: ${loadTargetUrl ?: "-"} | " +
                        "加载令牌: $expectedLoadToken | 阶段: ${loadPhaseLabel(loadPhase)}"
                }
                handleLoadFailure(fromWatchdog = true)
            }
        }
    }

    private fun endLoadWatchdog() {
        loadWatchdogJob?.cancel()
        loadWatchdogJob = null
    }

    /**
     * 新一次加载、会话开始或前后台周期切换：作废尚未完成的后台抓帧，允许下一周期重新抓。
     * [clearFrame] 为假时保留当前缓存帧，只作废在途结果。
     */
    private fun resetBackgroundCapture(clearFrame: Boolean = true) {
        backgroundCaptureToken++
        backgroundCaptureAttempted = false
        backgroundCaptureJob = null
        if (clearFrame) replaceBackgroundRestoreFrame(null)
    }

    /**
     * 换掉后台恢复帧；同样交给 GC，不手动回收。
     * 缓存帧也是屏幕该显示什么的一部分，换掉后立刻派生。只在控制层线程调用。
     */
    private fun replaceBackgroundRestoreFrame(frame: Bitmap?) {
        _backgroundRestoreFrame.value = frame
        settlePresentation()
    }

    /** 清掉进行中的加载与切集状态，不改变会话本身。 */
    private fun resetLoadState() {
        loadGeneration++
        loadPhase = LoadPhase.None
        endBufferingIndicator()
        endLoadWatchdog()
        readyState = ReadyState.Idle
        frameWaitJob?.cancel()
        frameWaitJob = null
        cancelSurfaceRestore()
        switchPlaybackStarted = false
        switchFramePending = false
        switchPlayPauseQueued = null
        suspendSwitchFrameWatchdog()
        clearSwitchFrameDeadline()
        _switching.value = false
        _restoring.value = false
        restartFromStartAfterRestore = false
        loadTargetIndex = NO_MEDIA
        loadTargetUrl = null
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
        requiredSeekSerial = 0
        resetSeekForNewLoad()
        resumeLookupDone = true
        switchOriginIndex = NO_MEDIA
        restoreAtEndOfEpisode = false
        switchOriginEnded = false
        expectedLoadToken = 0
        switchFailureReported = false
        holdBlack = false
        screenFrame = ScreenFrame.None
        switchOutputLive = false
        originAspectRatio = null
        settlePresentation()
        resetBackgroundCapture()
    }

    /** 开始一轮加载：续播位置未查明前不允许判定就绪，seek 序号从零比较。 */
    private fun beginLoadTracking() {
        loadGeneration++
        beginReadyTracking()
        loadStartPositionMillis = 0
        loadDurationMillis = 0
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
        requiredSeekSerial = 0
        resumeLookupDone = _resumeMode.value == ResumeMode.FromStart
        userSeeked = false
    }

    /** 新加载作废旧跳转：上一代的预览、落定与执行回报都不再有效。 */
    private fun resetSeekForNewLoad() {
        resetSeekPreviewState()
        clearLandingSeek()
    }

    /** 丢弃整段预览状态：预览位置、末尾下发目标、兜底计时与静音，以及上一代的执行回报。 */
    private fun resetSeekPreviewState() {
        previewEndJob?.cancel()
        previewEndJob = null
        _seekPreviewMillis.value = null
        lastPreviewSeekTargetMillis = null
        lastPreviewSeekId = null
        lastExecutedSeekId = null
        releasePreviewMute()
    }

    /** 进入一轮加载，并清掉上一轮的新帧等待。 */
    private fun beginReadyTracking() {
        readyState = ReadyState.Idle
        targetHasVideo = true
        // 上一轮加载自己的画面不再算数；切集起点的原片画面仍然沿用。
        if (screenFrame == ScreenFrame.Target) screenFrame = ScreenFrame.None
        switchFramePending = false
        suspendSwitchFrameWatchdog()
        clearSwitchFrameDeadline()
        frameWaitJob?.cancel()
        frameWaitJob = null
        cancelSurfaceRestore()
        settlePresentation()
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
            // 用户已经自己跳转过：查询到的续播位置作废，不能再把画面拉回去。
            resumePositionMillis = if (userSeeked) null else record?.positionMillis?.takeIf { it > 0 }
            resumeLookupDone = true
            applyResumePosition()
            val deferred = deferredReadyNeedsFrame
            deferredReadyNeedsFrame = null
            // 没有续播 seek 时，查询完成前收到的首帧仍然有效；有 seek 时等 seek 后的新信号。
            if (!seekPendingForReady && deferred != null) handleReadySignal(loadPhase, deferred)
            finishLoadWithoutSurface()
            // 续播查询完成后按条件重启兜底；有 seek 时由兜底循环顺延。
            armSwitchFrameFallback()
        }
    }

    /** 续播 seek 必须在就绪前发出；发出后只认序号不小于它的 ready，之后的 seek 照常接受。 */
    private fun applyResumePosition() {
        if (!resumeLookupDone) return
        // 用户已经自己跳转过：不再补发续播 seek。
        if (userSeeked) {
            resumePositionMillis = null
            return
        }
        val position = resumePositionMillis ?: return
        if (!mediaLoaded) return
        resumePositionMillis = null
        savedPositionMillis = position
        loadStartPositionMillis = position
        if (loadPhase != LoadPhase.None) {
            seekPendingForReady = true
            requiredSeekSerial = engine.seekTo(position).serial
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
        // 屏上仍是原片画面时比例不跟着换；目标画面接管后才用这个新比例。
        settlePresentation()
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
        override fun onMediaLoaded(targetUrl: String?, loadToken: Int, hasVideo: Boolean) {
            scope.launch { target.onMediaLoaded(targetUrl, loadToken, hasVideo) }
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

        override fun onSeekExecuted(seekId: Long, targetMillis: Long) {
            scope.launch { target.onSeekExecuted(seekId, targetMillis) }
        }

        override fun onLoadActivity(activity: LoadActivity, loadToken: Int?) {
            scope.launch { target.onLoadActivity(activity, loadToken) }
        }

        override fun onFramePresented(loadToken: Int) {
            scope.launch { target.onFramePresented(loadToken) }
        }

        override fun onPauseChanged(paused: Boolean) {
            scope.launch { target.onPauseChanged(paused) }
        }

        override fun onPlaybackEnded(loadToken: Int) {
            scope.launch { target.onPlaybackEnded(loadToken) }
        }

        override fun onPlaybackFailed(loadToken: Int, errorText: String?) {
            scope.launch { target.onPlaybackFailed(loadToken, errorText) }
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

        /** 拖动预览重发 seek 的目标差阈值：目标与最后一次下发相差超过这段时长就补一跳。 */
        const val PREVIEW_SEEK_DEAD_ZONE_MILLIS = 1_000L

        /**
         * 预览与落定目标离片尾留出的安全余量。必须严格大于「播完」容差：
         * 播完判据是「位置 ≥ 时长 − 容差」，余量取同比只会把目标夹在播完带内。
         */
        const val PREVIEW_END_MARGIN_MILLIS = 2_000L

        /** 落定通知的进程级序号：跨控制器实例也单调，界面据此只撤掉比它旧的保持值。 */
        internal val settlementIds = AtomicLong(0L)

        /** 拖动预览的心跳周期；界面在拖动期间按此周期通知控制层。 */
        const val PREVIEW_HEARTBEAT_MILLIS = 1_000L

        /** 拖动预览的兜底窗口：心跳停这么久才收尾，容忍两次心跳丢失。 */
        const val PREVIEW_IDLE_TIMEOUT_MILLIS = 3_000L

        /** 滚轮停滚后落定的窗口；窗口内再来一档就顺延。 */
        const val WHEEL_SETTLE_MILLIS = 1_000L

        /** 缓冲指示的延迟：超过这个时长仍未就绪才显示提示。 */
        const val BUFFER_INDICATOR_DELAY_MILLIS = 1000L

        /** 起播后等位置前进的兜底时长；超时按就绪收尾。 */
        const val FRAME_FALLBACK_MILLIS = 2_000L

        /** 挂载后等播放核心首个新帧的时长；仍无新帧只翻转一次视频输出。 */
        const val SURFACE_RESTORE_WAIT_MILLIS = 3_000L

        /** 完全无加载事件时看门狗才会触发的时长；任一加载事件都会重置。 */
        const val LOAD_WATCHDOG_MILLIS = 60_000L

        /** 切集后等新帧上屏的总窗口；从切集开始或最后一次加载事件起算。 */
        const val NO_FRAME_WINDOW_MILLIS = 60_000L

        private const val NO_MEDIA = -1

        /** 引擎的位置回调比落库所需的频率高得多。 */
        private const val SAVE_INTERVAL_MILLIS = 5_000L

        /** 位置与时长的差在这个范围内就算播到了结尾。 */
        private const val END_TOLERANCE_MILLIS = 1_000L

        /** 定时暂停的分钟换算。 */
        private const val MINUTE_MILLIS = 60_000L
    }
}

/**
 * 区分控制器自身 resume 产生的加载事件回声与真实加载活动。
 * resume 的回声只表现为同一加载令牌的 PLAYBACK_RESTART，最多忽略 [MAX_RESUME_ECHOES] 次；
 * 只有同一令牌的 START_FILE/FILE_LOADED/SEEK/END_FILE 才结束回声期，
 * 旧代际事件不会提前解除，之后的 PLAYBACK_RESTART 重新按真实事件处理。
 */
internal class ResumeActivityGate {

    private var resumeToken: Int? = null
    private var remainingEchoes = 0

    /** 控制器下发 resume 时绑定本次加载令牌，并重置回声次数上限。 */
    fun onResume(loadToken: Int) {
        resumeToken = loadToken
        remainingEchoes = MAX_RESUME_ECHOES
    }

    /** 返回 true 表示该事件是自身 resume 的回声，应忽略；真实活动返回 false。 */
    fun shouldIgnore(activity: LoadActivity, loadToken: Int?): Boolean {
        val sameLoad = loadToken != null && loadToken == resumeToken
        if (activity == LoadActivity.PlaybackRestart) {
            if (!sameLoad || remainingEchoes <= 0) return false
            remainingEchoes--
            return true
        }
        // 只有同一加载的真实事件才结束回声期；旧代际事件不能提前解除。
        if (sameLoad && (activity == LoadActivity.StartFile || activity == LoadActivity.FileLoaded ||
                activity == LoadActivity.Seek || activity == LoadActivity.EndFile)
        ) {
            resumeToken = null
            remainingEchoes = 0
        }
        return false
    }

    /** 切集收尾时清除回声期，避免影响下一次加载。 */
    fun reset() {
        resumeToken = null
        remainingEchoes = 0
    }

    private companion object {
        /** 一次 resume 最多吞掉的回声事件数；超出后按真实事件刷新窗口。 */
        const val MAX_RESUME_ECHOES = 3
    }
}

/** 落定 seek 的通知：一次落定一个 [id]，界面只撤掉比它旧的进度保持值。 */
data class SeekSettlement(val id: Long, val targetMillis: Long)
