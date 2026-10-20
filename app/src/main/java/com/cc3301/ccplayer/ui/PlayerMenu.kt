package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.SleepTimerOption
import com.cc3301.ccplayer.playback.SleepTimerState
import com.cc3301.ccplayer.settings.OrientationMode

/** “更多”面板里可展开为勾选列表的分组。 */
enum class MoreSection { Decode, Loop, Orientation, SleepTimer }

/** 播放页选项：取值与显示文案。 */
data class PlayerOption<T>(val value: T, val label: OptionLabel)

val decodeModeOptions: List<PlayerOption<DecodeMode>> =
    DecodeMode.entries.map { PlayerOption(it, OptionLabel(decodeModeLabel(it))) }

val loopModeOptions: List<PlayerOption<LoopMode>> =
    LoopMode.entries.map { PlayerOption(it, OptionLabel(loopModeLabel(it))) }

val orientationModeOptions: List<PlayerOption<OrientationMode>> =
    OrientationMode.entries.map { PlayerOption(it, OptionLabel(orientationModeLabel(it))) }

/** 定时暂停的固定选项；自定义分钟由输入框单独处理。 */
val sleepTimerOptions: List<PlayerOption<SleepTimerOption>> = listOf(
    PlayerOption(SleepTimerOption.AfterDuration(10), OptionLabel(R.string.player_sleep_timer_10_minutes)),
    PlayerOption(SleepTimerOption.AfterDuration(30), OptionLabel(R.string.player_sleep_timer_30_minutes)),
    PlayerOption(SleepTimerOption.AfterDuration(60), OptionLabel(R.string.player_sleep_timer_1_hour)),
    PlayerOption(SleepTimerOption.AfterDuration(120), OptionLabel(R.string.player_sleep_timer_2_hours)),
    PlayerOption(SleepTimerOption.EndOfEpisode, OptionLabel(R.string.player_sleep_timer_end_of_episode)),
)

/** 选项是否对应当前取值。 */
fun <T> isOptionChecked(option: PlayerOption<T>, selected: T): Boolean = option.value == selected

/** 定时选项的勾选：倒计时剩余时间不显示，固定时长不标勾，只有播完本集能按状态判断。 */
fun isSleepTimerOptionChecked(option: SleepTimerOption, state: SleepTimerState): Boolean =
    option == SleepTimerOption.EndOfEpisode && state == SleepTimerState.EndOfEpisode
