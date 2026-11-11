package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.SleepTimerOption
import com.cc3301.ccplayer.settings.OrientationMode

/**
 * 播放页的动作接口：每个动作一个方法，不装数据。
 */
interface PlayerActions {
    fun playPause()

    fun previous()

    fun next()

    fun seekPreview(positionMillis: Long, requestSeek: Boolean)

    fun seekCommit()

    fun seekBy(deltaMillis: Long): Long

    fun seekStep(deltaMillis: Long): Long?

    fun seekHeartbeat()

    fun selectPlaylistItem(media: MediaFile)

    fun selectDecodeMode(mode: DecodeMode)

    fun selectLoopMode(mode: LoopMode)

    fun selectOrientationMode(mode: OrientationMode)

    fun toggleAutoPlayNext()

    fun selectSleepTimer(option: SleepTimerOption)

    fun cancelSleepTimer()

    fun selectSubtitle(item: SubtitleMenuItem)

    fun toggleRotation()

    /** 顶栏返回：准备期撤回打开，其余结束播放或按返回规则处理。 */
    fun back()
}

/** 播放页的接入口：宿主与平台接入点。 */
class PlayerScreenAccess(
    val surfaceCallback: MpvTextureView.Callback,
    val levelControls: PlayerLevelControls,
)
