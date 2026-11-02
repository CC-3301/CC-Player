package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackController
import com.cc3301.ccplayer.playback.PlaybackState

/** 播放/暂停键投递的动作：显式目标，不是翻转请求。 */
enum class PlayerPlayPauseCommand { Pause, Resume }

/**
 * 播放/暂停键此刻该投递哪个命令。
 *
 * 按引擎上报的真实状态解析；切集窗口状态是 Loading，按引擎的播放意图取反，
 * 命令仍是显式目标，同一命令重复投递不改变结果。
 * 首次加载与播完返回 null，由控制层按现有规则收尾。
 */
fun playPauseCommand(
    state: PlaybackState,
    switching: Boolean,
    pausedIntent: Boolean,
): PlayerPlayPauseCommand? = when {
    state == PlaybackState.Playing -> PlayerPlayPauseCommand.Pause
    state == PlaybackState.Paused -> PlayerPlayPauseCommand.Resume
    switching -> if (pausedIntent) PlayerPlayPauseCommand.Resume else PlayerPlayPauseCommand.Pause
    else -> null
}

/** 投递播放/暂停命令：同一命令重复投递不改变结果。 */
fun applyPlayPauseCommand(
    command: PlayerPlayPauseCommand,
    pause: () -> Unit,
    resume: () -> Unit,
) {
    when (command) {
        PlayerPlayPauseCommand.Pause -> pause()
        PlayerPlayPauseCommand.Resume -> resume()
    }
}

/**
 * 播放/暂停键点击：按引擎上报状态解析一次命令后投递。
 *
 * 界面与测试都走这个入口，不复制解析与投递分支；播完或未就绪交给控制层重开并留痕。
 */
fun PlaybackController.onPlayPauseClick() {
    when (val command = playPauseCommand(state.value, switching.value, playPauseIntentPaused)) {
        null -> togglePlayPause()
        else -> applyPlayPauseCommand(command, pause = { pause() }, resume = { resume() })
    }
}
