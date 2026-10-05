package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.compareNatural
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放控制层：把播放指令翻译成引擎调用，把引擎回调汇成 [PlaybackState]。
 *
 * 播放列表取自当前目录的媒体文件，恒按名称自然排序（ADR-0007）；上一集/下一集取该序列的相邻项，
 * 与列表视图当前的排序无关。当前媒体播完后按循环模式与自动连播开关决定下一步。
 * 循环模式与解码模式的快捷切换只作用于本次会话。
 */
class PlaybackController(private val engine: PlaybackEngine) : PlaybackEngine.Listener {

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

    private val byNaturalName = Comparator<MediaFile> { left, right -> compareNatural(left.name, right.name) }

    /** 会话里是否有已打开的媒体。 */
    private var active = false

    init {
        engine.setListener(this)
    }

    /**
     * 以 [files] 为播放列表起播 [current]；列表只收媒体文件，按名称自然排序。
     * [current] 不在列表里时不动作。
     */
    fun openPlaylist(files: List<MediaFile>, current: MediaFile) {
        val playlist = files.filterNot { it.isDirectory }.sortedWith(byNaturalName)
        val index = playlist.indexOfFirst { it.location == current.location }
        if (index < 0) return
        _playlist.value = playlist
        playAt(index)
    }

    /** 切换到播放列表第 [index] 项；超出范围时不动作。 */
    fun playAt(index: Int) {
        val media = _playlist.value.getOrNull(index) ?: return
        _currentIndex.value = index
        _progress.value = PlaybackProgress()
        _seekPreviewMillis.value = null
        active = true
        _state.value = PlaybackState.Loading
        engine.start(media.location)
    }

    /** 名称自然排序下的一集；列表末尾时不动作。 */
    fun next() {
        playAt(_currentIndex.value + 1)
    }

    /** 名称自然排序下的上一集；列表开头时不动作。 */
    fun previous() {
        playAt(_currentIndex.value - 1)
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
        _seekPreviewMillis.value = null
        _decodeMode.value = DecodeMode.Hardware
        _loopMode.value = LoopMode.Sequential
        _videoAspectRatio.value = null
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
        _progress.value = PlaybackProgress(positionMillis, durationMillis)
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        if (!active || width <= 0 || height <= 0) return
        _videoAspectRatio.value = width.toDouble() / height
    }

    private companion object {
        const val NO_MEDIA = -1
    }
}
