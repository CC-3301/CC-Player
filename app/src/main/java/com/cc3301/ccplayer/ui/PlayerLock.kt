package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 解锁按钮淡出的时长，毫秒；第 4 秒开始淡出、第 5 秒完全消失。 */
const val UNLOCK_FADE_OUT_MILLIS = CONTROLS_FADE_OUT_MILLIS

/** 解锁按钮无操作后开始淡出的时刻，毫秒。 */
const val UNLOCK_FADE_START_MILLIS = PLAYER_AUTO_HIDE_MILLIS - UNLOCK_FADE_OUT_MILLIS

/**
 * 播放页锁定状态：锁定时手势与控制条一并失效，只留左上角解锁按钮。
 *
 * 解锁按钮在锁定时出现，无操作满 [UNLOCK_FADE_START_MILLIS] 开始淡出，
 * 满 [PLAYER_AUTO_HIDE_MILLIS] 完全消失；锁定态点画面重新出现。
 * 解锁按钮点一次立即解锁，不进入确认态（ADR-0014）。
 */
class PlayerLock(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
    val fadeOutMillis: Long = UNLOCK_FADE_OUT_MILLIS,
) {
    var locked by mutableStateOf(false)
        private set

    /** 解锁按钮是否在场上（含淡出中）。 */
    var unlockVisible by mutableStateOf(false)
        private set

    /** 解锁按钮是否正在淡出。 */
    var unlockFading by mutableStateOf(false)
        private set

    /** 解锁按钮本次出现的时刻，用于自动隐藏计时。 */
    var unlockShownAtMillis by mutableLongStateOf(0L)
        private set

    /** 本次淡出的开始时刻。 */
    var unlockFadeOutStartedAtMillis by mutableLongStateOf(0L)
        private set

    /** 点锁定按钮：未锁定时上锁并显示解锁按钮，锁定时点一次立即解锁。 */
    fun tapLockButton() {
        if (locked) {
            locked = false
            unlockVisible = false
            unlockFading = false
            return
        }
        locked = true
        showUnlock()
    }

    /** 锁定态下点画面：重新显示解锁按钮，并重新开始自动隐藏计时。 */
    fun tapScreen() {
        if (locked) showUnlock()
    }

    /** 距解锁按钮开始淡出的剩余毫秒；不在场或已在淡出时返回 -1。 */
    fun millisUntilUnlockFadeOut(): Long {
        if (!locked || !unlockVisible || unlockFading) return -1L
        return (UNLOCK_FADE_START_MILLIS - (elapsedMillis() - unlockShownAtMillis)).coerceAtLeast(0L)
    }

    /** 距解锁按钮完全消失的剩余毫秒；不在淡出时返回 -1。 */
    fun millisUntilUnlockHidden(): Long {
        if (!unlockFading) return -1L
        return (fadeOutMillis - (elapsedMillis() - unlockFadeOutStartedAtMillis)).coerceAtLeast(0L)
    }

    /** 按钮在场且无操作满 [UNLOCK_FADE_START_MILLIS] 后开始淡出。 */
    fun beginUnlockFadeOutIfExpired() {
        if (!locked || !unlockVisible || unlockFading) return
        if (elapsedMillis() - unlockShownAtMillis >= UNLOCK_FADE_START_MILLIS) {
            unlockFading = true
            unlockFadeOutStartedAtMillis = elapsedMillis()
        }
    }

    /** 淡出满 [fadeOutMillis] 后移除解锁按钮。 */
    fun completeUnlockFadeOut() {
        if (!unlockFading) return
        if (elapsedMillis() - unlockFadeOutStartedAtMillis >= fadeOutMillis) {
            unlockVisible = false
            unlockFading = false
        }
    }

    private fun showUnlock() {
        unlockShownAtMillis = elapsedMillis()
        unlockVisible = true
        unlockFading = false
    }
}
