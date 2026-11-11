package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaKind
import com.cc3301.ccplayer.library.mediaKindOf
import com.cc3301.ccplayer.playback.PlaybackController
import com.cc3301.ccplayer.playback.PlaybackFailure
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.SeekSettlement
import com.cc3301.ccplayer.playback.SurfacePresentation
import com.cc3301.ccplayer.settings.OrientationMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 播放页的状态持有者：收集播放控制层的事实，组装会话事实、内容与播放页阶段。
 *
 * 播放会话开始时挂上播放控制层，会话结束时断开并清空；播放页隐藏期间继续收集，
 * 常亮、比例与加载失败提示这些界面副作用依赖同一批结论。
 */
class PlayerScreenViewModel : ViewModel() {

    private var controller: PlaybackController? = null
    private var collection: Job? = null

    private var playbackStateValue by mutableStateOf<PlaybackState>(PlaybackState.Idle)
    private var progressValue by mutableStateOf(PlaybackProgress())
    private var seekPreviewMillisValue by mutableStateOf<Long?>(null)
    private var seekSettlementValue by mutableStateOf<SeekSettlement?>(null)
    private var currentIndexValue by mutableStateOf(-1)
    private var surfacePresentationValue by mutableStateOf(SurfacePresentation())
    private var menuFacts by mutableStateOf(PlayerMenuFacts())
    private var contentState by mutableStateOf(PlayerScreenContent())
    private var switchingValue by mutableStateOf(false)
    private var restoringValue by mutableStateOf(false)
    private var engineBuffering by mutableStateOf(false)
    private var failed by mutableStateOf(false)

    private val _failures = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)

    /** 引擎当前的缓冲事实；打开交接期间的实际显示值由界面与入口阶段合成。 */
    val controllerBuffering: Boolean get() = engineBuffering

    /** 控制层是否在切集。 */
    val switching: Boolean get() = switchingValue

    /** 控制层是否在恢复原片。 */
    val restoring: Boolean get() = restoringValue

    /** 当前播放状态。 */
    val playbackState: PlaybackState get() = playbackStateValue

    /** 当前播放位置与时长。 */
    val progress: PlaybackProgress get() = progressValue

    /** 最近一次播放是否以失败收尾。 */
    val playbackFailed: Boolean get() = failed

    /** 当前目标条目；没有条目时为 null。 */
    val currentMedia: MediaFile? get() = contentState.playlist.getOrNull(currentIndexValue)

    /** 屏幕上现在该显示什么。 */
    val targetSurface: SurfacePresentation get() = surfacePresentationValue

    /** 加载失败事件：界面按它提示，并决定首次加载失败是否退回浏览页。 */
    val failures: SharedFlow<PlaybackFailure> = _failures.asSharedFlow()

    /** 挂上播放控制层并开始收集；同一条控制层重复挂载不重复收集。 */
    fun attach(controller: PlaybackController) {
        if (this.controller === controller && collection?.isActive == true) return
        detach()
        this.controller = controller
        sync(controller)
        collection = viewModelScope.launch {
            launch { controller.state.collect { value -> playbackStateValue = value } }
            launch { controller.progress.collect { value -> progressValue = value } }
            launch { controller.seekPreviewMillis.collect { value -> seekPreviewMillisValue = value } }
            launch { controller.seekSettlement.collect { value -> seekSettlementValue = value } }
            launch { controller.playlist.collect { value -> contentState = contentState.copy(playlist = value) } }
            launch { controller.currentIndex.collect { value -> currentIndexValue = value } }
            launch { controller.decodeMode.collect { value -> menuFacts = menuFacts.copy(decodeMode = value) } }
            launch { controller.loopMode.collect { value -> menuFacts = menuFacts.copy(loopMode = value) } }
            launch {
                controller.sleepTimerState.collect { value ->
                    menuFacts = menuFacts.copy(sleepTimerState = value)
                }
            }
            launch {
                controller.sleepTimerOption.collect { value ->
                    menuFacts = menuFacts.copy(sleepTimerOption = value)
                }
            }
            launch {
                controller.subtitleSelection.collect { value ->
                    menuFacts = menuFacts.copy(subtitleSelection = value)
                }
            }
            launch { controller.switching.collect { value -> switchingValue = value } }
            launch { controller.restoring.collect { value -> restoringValue = value } }
            launch { controller.buffering.collect { value -> engineBuffering = value } }
            launch { controller.playbackFailed.collect { value -> failed = value } }
            launch { controller.playbackFailures.collect { value -> _failures.tryEmit(value) } }
            launch { controller.surfacePresentation.collect { value -> surfacePresentationValue = value } }
            launch {
                controller.subtitleTracks.collect { value ->
                    contentState = contentState.copy(subtitleTracks = value)
                }
            }
            launch {
                controller.externalSubtitles.collect { value ->
                    contentState = contentState.copy(externalSubtitles = value)
                }
            }
        }
    }

    /** 离开播放页：停止收集并清空事实。 */
    fun detach() {
        collection?.cancel()
        collection = null
        controller = null
        playbackStateValue = PlaybackState.Idle
        progressValue = PlaybackProgress()
        seekPreviewMillisValue = null
        seekSettlementValue = null
        currentIndexValue = -1
        surfacePresentationValue = SurfacePresentation()
        menuFacts = PlayerMenuFacts()
        contentState = PlayerScreenContent()
        switchingValue = false
        restoringValue = false
        engineBuffering = false
        failed = false
    }

    /** 组装当前会话事实；入口事实与来自设置的菜单事实由界面在这一帧同步传入。 */
    fun session(
        entryPhase: EntryPhase?,
        preparesInPage: Boolean,
        bufferingShown: Boolean,
        orientationMode: OrientationMode,
        orientationOverride: ScreenOrientation?,
        screenOrientation: ScreenOrientation,
        autoPlayNext: Boolean,
    ): PlayerScreenState {
        val media = currentMedia
        return PlayerScreenState(
            progress = PlayerProgressFacts(
                state = playbackStateValue,
                progress = progressValue,
                seekPreviewMillis = seekPreviewMillisValue,
                seekSettlement = seekSettlementValue,
                currentIndex = currentIndexValue,
                surfacePresentation = surfacePresentationValue,
                audioTarget = media != null && mediaKindOf(media.name) == MediaKind.Audio,
            ),
            loading = PlayerLoadingFacts(
                phase = playerScreenPhase(
                    PlayerScreenLoadFacts(
                        entryPhase = entryPhase,
                        preparesInPage = preparesInPage,
                        playbackState = playbackStateValue,
                        switching = switchingValue,
                        restoring = restoringValue,
                    ),
                ),
                buffering = bufferingShown,
            ),
            menu = PlayerMenuFacts(
                decodeMode = menuFacts.decodeMode,
                loopMode = menuFacts.loopMode,
                orientationMode = orientationMode,
                orientationOverride = orientationOverride,
                screenOrientation = screenOrientation,
                autoPlayNext = autoPlayNext,
                sleepTimerState = menuFacts.sleepTimerState,
                sleepTimerOption = menuFacts.sleepTimerOption,
                subtitleSelection = menuFacts.subtitleSelection,
            ),
        )
    }

    /** 组装当前内容；封面由界面按目标缓存取出。 */
    fun content(coverFile: File?): PlayerScreenContent = contentState.copy(coverFile = coverFile)

    private fun sync(controller: PlaybackController) {
        playbackStateValue = controller.state.value
        progressValue = controller.progress.value
        seekPreviewMillisValue = controller.seekPreviewMillis.value
        seekSettlementValue = controller.seekSettlement.value
        currentIndexValue = controller.currentIndex.value
        surfacePresentationValue = controller.surfacePresentation.value
        menuFacts = PlayerMenuFacts(
            decodeMode = controller.decodeMode.value,
            loopMode = controller.loopMode.value,
            sleepTimerState = controller.sleepTimerState.value,
            sleepTimerOption = controller.sleepTimerOption.value,
            subtitleSelection = controller.subtitleSelection.value,
        )
        contentState = PlayerScreenContent(
            playlist = controller.playlist.value,
            subtitleTracks = controller.subtitleTracks.value,
            externalSubtitles = controller.externalSubtitles.value,
        )
        switchingValue = controller.switching.value
        restoringValue = controller.restoring.value
        engineBuffering = controller.buffering.value
        failed = controller.playbackFailed.value
    }
}
