package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.SeekSettlement
import com.cc3301.ccplayer.playback.SleepTimerOption
import com.cc3301.ccplayer.playback.SleepTimerState
import com.cc3301.ccplayer.playback.SubtitleSelection
import com.cc3301.ccplayer.playback.SubtitleTrack
import com.cc3301.ccplayer.playback.SurfacePresentation
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.settings.SettingsRepository
import java.io.File

/**
 * 播放页会话事实：播放页要显示的全部稳定值，分播放进度、加载阶段、菜单偏好三组。
 * 列表、文件与回调不放进这里，由内容参数与接入口参数另行传入。
 */
data class PlayerScreenState(
    val progress: PlayerProgressFacts = PlayerProgressFacts(),
    val loading: PlayerLoadingFacts = PlayerLoadingFacts(),
    val menu: PlayerMenuFacts = PlayerMenuFacts(),
)

/** 播放进度组：播放状态、位置、拖动预览与屏幕呈现。 */
data class PlayerProgressFacts(
    val state: PlaybackState = PlaybackState.Idle,
    val progress: PlaybackProgress = PlaybackProgress(),
    val seekPreviewMillis: Long? = null,
    val seekSettlement: SeekSettlement? = null,
    /** 当前播放列表位置；没有条目时为 -1。 */
    val currentIndex: Int = -1,
    val surfacePresentation: SurfacePresentation = SurfacePresentation(),
    /** 当前目标是纯音频：只有这类目标使用封面层。 */
    val audioTarget: Boolean = false,
)

/** 加载阶段组：加载阶段与引擎缓冲事实。 */
data class PlayerLoadingFacts(
    val phase: PlayerScreenPhase = PlayerScreenPhase.Ready,
    val buffering: Boolean = false,
)

/** 菜单偏好组：解码、循环、方向、连播、睡眠定时与字幕选择。 */
data class PlayerMenuFacts(
    val decodeMode: DecodeMode = DecodeMode.Hardware,
    val loopMode: LoopMode = LoopMode.Sequential,
    val orientationMode: OrientationMode = SettingsRepository.DEFAULT_ORIENTATION_MODE,
    val orientationOverride: ScreenOrientation? = null,
    val screenOrientation: ScreenOrientation = ScreenOrientation.Portrait,
    val autoPlayNext: Boolean = SettingsRepository.DEFAULT_AUTO_PLAY_NEXT,
    val sleepTimerState: SleepTimerState = SleepTimerState.Off,
    val sleepTimerOption: SleepTimerOption? = null,
    val subtitleSelection: SubtitleSelection = SubtitleSelection.Off,
)

/** 播放页的内容：列表与文件。事实对象不放这些，避免整组事实被判不稳定。 */
data class PlayerScreenContent(
    val playlist: List<MediaFile> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val externalSubtitles: List<MediaFile> = emptyList(),
    val coverFile: File? = null,
)
