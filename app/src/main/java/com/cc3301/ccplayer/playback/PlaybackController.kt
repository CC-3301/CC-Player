package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.compareNatural
import com.cc3301.ccplayer.progress.MediaProgress
import com.cc3301.ccplayer.progress.PlaybackProgressRepository
import com.cc3301.ccplayer.settings.ResumeMode
import kotlinx.coroutines.CoroutineScope
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
 * 与列表视图当前的排序无关。当前媒体播完后按循环模式与自动连播开关决定下一步。
 * 循环模式与解码模式的快捷切换只作用于本次会话。
 *
 * 起播位置按 [ResumeMode] 取上次位置或从头；播放中按间隔落进度记录，位置到达时长（播到 100%）时清除记录。
 *
 * 定时暂停按 [SleepTimerOption] 到点暂停，或等当前文件自然播完时暂停（ADR-0015）；定时随会话结束清除。
 */
class PlaybackController(
    private val engine: PlaybackEngine,
    private val progressStore: PlaybackProgressRepository,
    private val scope: CoroutineScope,
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

    /** 正在切集：原视频暂停，画面冻结，等目标就绪后替换。 */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /** 缓冲指示是否显示：加载超过 [BUFFER_INDICATOR_DELAY_MILLIS] 仍未就绪时为真。 */
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

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

    /** 切集前原视频的下标与位置，失败或取消时回到这里。 */
    private var switchOriginIndex = NO_MEDIA
    private var switchOriginPositionMillis = 0L
    private var switchOriginDurationMillis = 0L

    /** 恢复原视频时等待文件打开后跳回的进度。 */
    private var restorePositionMillis = 0L

    /** 缓冲指示的延迟任务；加载结束时取消。 */
    private var bufferingJob: Job? = null

    init {
        engine.setListener(this)
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

    /** 切换到播放列表第 [index] 项；超出范围或正在加载时不动作。 */
    fun playAt(index: Int) {
        val media = _playlist.value.getOrNull(index) ?: return
        // 切集缓冲期间重复切集不生效；首次加载还没开始播放时仍可直接改目标。
        if (loadPhase == LoadPhase.Switching || loadPhase == LoadPhase.Restoring) return
        if (active) {
            startSwitch(index, media)
        } else {
            startSession(index, media)
        }
    }

    /** 按媒体位置切换，不把列表视图的下标当作播放下标。 */
    fun play(media: MediaFile) {
        val index = _playlist.value.indexOfFirst { it.location == media.location }
        if (index >= 0) playAt(index)
    }

    /** 名称自然排序下的一集；列表末尾时不动作。 */
    fun next() {
        playAt(_currentIndex.value + 1)
    }

    /** 名称自然排序下的上一集；列表开头时不动作。 */
    fun previous() {
        playAt(_currentIndex.value - 1)
    }

    /**
     * 取消进行中的切集并回到原视频：恢复原位置并保持暂停。
     * 返回是否确实取消了切集；没有切集时返回 false。
     */
    fun cancelSwitch(): Boolean {
        if (loadPhase != LoadPhase.Switching) return false
        restoreOrigin()
        return true
    }

    /** 新起播一个会话：没有旧媒体要保留，画面从黑屏开始。 */
    private fun startSession(index: Int, media: MediaFile) {
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
        loadTargetIndex = index
        loadPhase = LoadPhase.Initial
        _switching.value = false
        _state.value = PlaybackState.Loading
        beginBufferingIndicator()
        engine.start(media.playbackLocation)
        engine.fadeInAudio(FADE_IN_MILLIS)
        lookUpResumePosition(index, media)
    }

    /**
     * 切集：立即暂停原视频保留当前画面，再加载目标；首帧就绪后替换。
     * 进度保持在原视频上，直到目标就绪。
     */
    private fun startSwitch(index: Int, media: MediaFile) {
        switchOriginIndex = _currentIndex.value
        switchOriginPositionMillis = playedPositionMillis
        switchOriginDurationMillis = playedDurationMillis
        _currentIndex.value = index
        loadTargetIndex = index
        loadPhase = LoadPhase.Switching
        _switching.value = true
        _seekPreviewMillis.value = null
        _subtitleTracks.value = emptyList()
        _externalSubtitles.value = emptyList()
        _subtitleSelection.value = SubtitleSelection.Off
        resumePositionMillis = null
        watchedToEnd = false
        mediaLoaded = false
        pausedAtEpisodeEnd = false
        savedPositionMillis = 0
        _state.value = PlaybackState.Loading
        beginBufferingIndicator()
        engine.pause()
        engine.start(media.playbackLocation)
        engine.fadeInAudio(FADE_IN_MILLIS)
        lookUpResumePosition(index, media)
    }

    fun pause() {
        if (!active) return
        paused = true
        engine.pause()
    }

    /**
     * 继续播放；播放位置停在文件末尾时从头重播当前文件，否则接在暂停处继续。
     */
    fun resume() {
        if (!active) return
        paused = false
        // 切集加载中的继续只记为意图，等目标文件就绪再出声。
        if (loadPhase == LoadPhase.Switching || loadPhase == LoadPhase.Restoring) return
        if (stoppedAtEndOfEpisode()) playAt(_currentIndex.value)
        engine.resume()
    }

    /** 音频输出中断（耳机拔出、蓝牙断连）：立即暂停，不自动恢复，等用户手动继续。 */
    fun handleAudioOutputLost() {
        if (!active) return
        paused = true
        engine.pause()
        _state.value = PlaybackState.Paused
    }

    fun togglePlayPause() {
        when (_state.value) {
            PlaybackState.Playing -> pause()
            PlaybackState.Paused -> resume()
            else -> Unit
        }
    }

    fun stop() {
        val indexToSave = if (loadPhase == LoadPhase.None) _currentIndex.value else switchOriginIndex
        if (active) _playlist.value.getOrNull(indexToSave)?.let { savePosition(media = it, force = true) }
        active = false
        paused = false
        engine.stop()
        _state.value = PlaybackState.Idle
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

    override fun onMediaLoaded() {
        if (!active) return
        mediaLoaded = true
        when (loadPhase) {
            LoadPhase.Initial -> {
                applyResumePosition()
                applyStartSubtitles(loadTargetIndex)
                // 暂停中加载不会再有首帧事件，文件打开即可停在首帧。
                if (paused) finishInitialLoad()
            }
            LoadPhase.Switching -> {
                applyResumePosition()
                applyStartSubtitles(loadTargetIndex)
                if (paused) finishSwitch() else engine.resume()
            }
            LoadPhase.Restoring -> finishRestore()
            LoadPhase.None -> Unit
        }
    }

    override fun onFirstFrameReady() {
        if (!active || !mediaLoaded) return
        when (loadPhase) {
            LoadPhase.Initial -> finishInitialLoad()
            LoadPhase.Switching -> finishSwitch()
            LoadPhase.Restoring, LoadPhase.None -> Unit
        }
    }

    override fun onPauseChanged(paused: Boolean) {
        if (!active) return
        // 首次加载被暂停后不会再等来首帧事件，文件已打开就直接进入暂停状态。
        if (loadPhase == LoadPhase.Initial && paused) {
            this.paused = true
            finishInitialLoad()
            return
        }
        // 加载期间引擎的暂停来自切集本身，不代表用户想停。
        if (loadPhase != LoadPhase.None) return
        this.paused = paused
        _state.value = if (paused) PlaybackState.Paused else PlaybackState.Playing
    }

    override fun onPlaybackFailed() {
        if (!active) return
        when (loadPhase) {
            LoadPhase.Switching -> {
                restoreOrigin()
                _playbackFailures.tryEmit(Unit)
            }
            LoadPhase.Initial -> {
                resetLoadState()
                active = false
                _state.value = PlaybackState.Ended
                _playbackFailures.tryEmit(Unit)
            }
            LoadPhase.Restoring -> {
                resetLoadState()
                _state.value = PlaybackState.Paused
            }
            LoadPhase.None -> Unit
        }
    }

    override fun onPlaybackEnded() {
        if (!active) return
        // 旧文件被替换时的结束事件不能触发自动连播。
        if (loadPhase != LoadPhase.None) return
        if (_sleepTimerState.value == SleepTimerState.EndOfEpisode) {
            _sleepTimerState.value = SleepTimerState.Off
            _sleepTimerOption.value = null
            pausedAtEpisodeEnd = true
            _state.value = PlaybackState.Paused
            return
        }
        active = false
        when {
            _loopMode.value == LoopMode.SingleLoop -> playAt(_currentIndex.value)
            !_autoPlayNext.value -> _state.value = PlaybackState.Ended
            _currentIndex.value < _playlist.value.lastIndex -> playAt(_currentIndex.value + 1)
            _loopMode.value == LoopMode.ListLoop -> playAt(0)
            else -> _state.value = PlaybackState.Ended
        }
    }

    override fun onPositionChanged(positionMillis: Long, durationMillis: Long) {
        if (!active) return
        // 加载中的位置属于新文件，切集时不能覆盖冻结画面上的原进度。
        if (loadPhase != LoadPhase.None) return
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
        _state.value = PlaybackState.Paused
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
        endBufferingIndicator()
        loadPhase = LoadPhase.None
        loadTargetIndex = _currentIndex.value
        _state.value = if (paused) PlaybackState.Paused else PlaybackState.Playing
    }

    /** 切集就绪：目标画面直接替换冻结画面，进度从新文件重新开始。 */
    private fun finishSwitch() {
        if (loadPhase != LoadPhase.Switching) return
        endBufferingIndicator()
        loadPhase = LoadPhase.None
        loadTargetIndex = _currentIndex.value
        _switching.value = false
        switchOriginIndex = NO_MEDIA
        playedPositionMillis = 0
        playedDurationMillis = 0
        _progress.value = PlaybackProgress()
        _state.value = if (paused) PlaybackState.Paused else PlaybackState.Playing
    }

    /** 失败或取消切集：重新打开原视频，停在切集前的位置。 */
    private fun restoreOrigin() {
        val origin = _playlist.value.getOrNull(switchOriginIndex)
        if (origin == null) {
            resetLoadState()
            return
        }
        endBufferingIndicator()
        loadPhase = LoadPhase.Restoring
        loadTargetIndex = switchOriginIndex
        _currentIndex.value = switchOriginIndex
        restorePositionMillis = switchOriginPositionMillis
        playedPositionMillis = switchOriginPositionMillis
        playedDurationMillis = switchOriginDurationMillis
        _progress.value = PlaybackProgress(switchOriginPositionMillis, switchOriginDurationMillis)
        savedPositionMillis = switchOriginPositionMillis
        resumePositionMillis = null
        mediaLoaded = false
        watchedToEnd = false
        pausedAtEpisodeEnd = false
        paused = true
        _subtitleTracks.value = emptyList()
        _externalSubtitles.value = emptyList()
        _subtitleSelection.value = SubtitleSelection.Off
        _state.value = PlaybackState.Paused
        engine.pause()
        engine.start(origin.playbackLocation)
    }

    /** 原视频重新打开完成：跳回切集前的位置并保持暂停，冻结画面随之撤掉。 */
    private fun finishRestore() {
        if (loadPhase != LoadPhase.Restoring) return
        mediaLoaded = true
        loadPhase = LoadPhase.None
        loadTargetIndex = _currentIndex.value
        _switching.value = false
        switchOriginIndex = NO_MEDIA
        if (restorePositionMillis > 0) engine.seekTo(restorePositionMillis)
        restorePositionMillis = 0
        applyStartSubtitles(_currentIndex.value)
        _state.value = PlaybackState.Paused
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

    private fun endBufferingIndicator() {
        bufferingJob?.cancel()
        bufferingJob = null
        _buffering.value = false
    }

    /** 清掉进行中的加载与切集状态，不改变会话本身。 */
    private fun resetLoadState() {
        endBufferingIndicator()
        _switching.value = false
        loadPhase = LoadPhase.None
        loadTargetIndex = NO_MEDIA
        switchOriginIndex = NO_MEDIA
        restorePositionMillis = 0
    }

    /** 按 [ResumeMode] 读记录：读到正的位置就在媒体加载后跳一次。 */
    private fun lookUpResumePosition(index: Int, media: MediaFile) {
        if (_resumeMode.value == ResumeMode.FromStart) return
        scope.launch {
            val record: MediaProgress? = progressStore.find(media.location)
            if (!active || loadTargetIndex != index) return@launch
            resumePositionMillis = record?.positionMillis?.takeIf { it > 0 }
            applyResumePosition()
        }
    }

    private fun applyResumePosition() {
        val position = resumePositionMillis ?: return
        if (!mediaLoaded) return
        resumePositionMillis = null
        savedPositionMillis = position
        engine.seekTo(position)
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

    /** 一次加载所处的阶段；切集与首次打开共用缓冲指示，但返回行为不同。 */
    private enum class LoadPhase { None, Initial, Switching, Restoring }

    companion object {
        /** 起播与跳转后的音频淡入时长，固定值。 */
        const val FADE_IN_MILLIS = 500L

        /** 缓冲指示的延迟：超过这个时长仍未就绪才显示提示。 */
        const val BUFFER_INDICATOR_DELAY_MILLIS = 500L

        private const val NO_MEDIA = -1

        /** 引擎的位置回调比落库所需的频率高得多。 */
        private const val SAVE_INTERVAL_MILLIS = 5_000L

        /** 位置与时长的差在这个范围内就算播到了结尾。 */
        private const val END_TOLERANCE_MILLIS = 1_000L

        /** 定时暂停的分钟换算。 */
        private const val MINUTE_MILLIS = 60_000L
    }
}
