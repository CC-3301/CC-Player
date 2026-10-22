package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 解锁按钮出现后自动隐藏的时长，毫秒。 */
const val UNLOCK_AUTO_HIDE_MILLIS = PLAYER_AUTO_HIDE_MILLIS

/**
 * 播放页锁定状态：锁定时手势与控制条一并失效，只留左上角解锁按钮。
 *
 * 解锁按钮在锁定时出现，[UNLOCK_AUTO_HIDE_MILLIS] 后自动隐藏；锁定态点画面重新出现。
 * 解锁按钮点一次立即解锁，不进入确认态（ADR-0014）。
 */
class PlayerLock(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var locked by mutableStateOf(false)
        private set

    /** 解锁按钮当前是否可见。 */
    var unlockVisible by mutableStateOf(false)
        private set

    /** 解锁按钮本次出现的时刻，用于自动隐藏计时。 */
    var unlockShownAtMillis by mutableStateOf(0L)
        private set

    /** 点锁定按钮：未锁定时上锁并显示解锁按钮，锁定时点一次立即解锁。 */
    fun tapLockButton() {
        if (locked) {
            locked = false
            unlockVisible = false
            return
        }
        locked = true
        showUnlock()
    }

    /** 锁定态下点画面：重新显示解锁按钮，并重新开始自动隐藏计时。 */
    fun tapScreen() {
        if (locked) showUnlock()
    }

    /** 解锁按钮显示满 [UNLOCK_AUTO_HIDE_MILLIS] 后隐藏；未满时保持。 */
    fun hideUnlockIfExpired() {
        if (!locked || !unlockVisible) return
        if (elapsedMillis() - unlockShownAtMillis >= UNLOCK_AUTO_HIDE_MILLIS) {
            unlockVisible = false
        }
    }

    private fun showUnlock() {
        unlockShownAtMillis = elapsedMillis()
        unlockVisible = true
    }
}
