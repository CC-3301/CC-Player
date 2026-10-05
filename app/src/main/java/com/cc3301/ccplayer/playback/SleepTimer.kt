package com.cc3301.ccplayer.playback

/** 定时暂停的时长范围，单位分钟。 */
val SLEEP_TIMER_MINUTES: IntRange = 1..1440

/**
 * 定时暂停的选项：四段固定时长与自定义时长都是「到点暂停」，[EndOfEpisode] 是「当前文件播完后暂停」。
 */
sealed interface SleepTimerOption {

    /** 从设定时刻起 [minutes] 分钟后暂停。 */
    data class AfterDuration(val minutes: Int) : SleepTimerOption

    /** 当前文件自然播完后暂停。 */
    data object EndOfEpisode : SleepTimerOption
}

/**
 * 解析自定义定时时长：只接受 [SLEEP_TIMER_MINUTES] 内的整数分钟。
 * 空输、非数字与越界都返回 null。
 */
fun customSleepMinutes(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in SLEEP_TIMER_MINUTES }

/** 定时暂停的会话状态；剩余时间不对外提供。 */
enum class SleepTimerState {
    /** 没设定时。 */
    Off,

    /** 到点暂停的倒计时进行中。 */
    Countdown,

    /** 等当前文件自然播完。 */
    EndOfEpisode,
}
