package com.cc3301.ccplayer.ui

import android.os.SystemClock

/** 同一按钮两次生效点击之间的最短间隔，毫秒。 */
const val PLAYER_BUTTON_DEBOUNCE_MILLIS = 300L

/**
 * 播放页上带按压去抖的按钮。
 *
 * 只有播放/暂停：它是状态开关，重复的一击会把刚生效的状态又切回去。
 * 上一集/下一集每击都要前移切集目标，不去抖。
 */
enum class PlayerPressButton {
    PlayPause,
}

/**
 * 播放页按钮的按压去抖：同一按钮在 [PLAYER_BUTTON_DEBOUNCE_MILLIS] 内只放行第一击。
 *
 * 按按钮分别计时，一个按钮被按下不影响其它按钮；被压掉的重复点击不延长窗口，
 * 窗口始终从上一击生效的时刻起算。
 */
class PlayerButtonDebounce(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lastAcceptedAtMillis = mutableMapOf<PlayerPressButton, Long>()

    /** 这一击是否生效：同一按钮距上一击生效满 [PLAYER_BUTTON_DEBOUNCE_MILLIS] 才再次生效。 */
    fun accept(button: PlayerPressButton): Boolean {
        val now = elapsedMillis()
        val last = lastAcceptedAtMillis[button]
        if (last != null && now - last < PLAYER_BUTTON_DEBOUNCE_MILLIS) return false
        lastAcceptedAtMillis[button] = now
        return true
    }
}
