package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * 按住按钮期间不淡出；解锁按钮点一次立即解锁并立即移除，不保留退场子树（ADR-0014）。
 */
class PlayerLock(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var locked by mutableStateOf(false)
        private set

    /** 解锁按钮是否在场上（含淡出中）；解锁动作立即置为 false，按钮随即离开组合。 */
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

    private var pressedCount by mutableIntStateOf(0)

    /** 解锁按钮按住中：按住期间不进入淡出，抬起后重新计时。 */
    val unlockPressing: Boolean
        get() = pressedCount > 0

    /** 点锁定按钮：未锁定时上锁并显示解锁按钮，锁定时点一次立即解锁并移除按钮。 */
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

    /** 解锁按钮按下：淡出中撤销淡出，按住期间不进入淡出。 */
    fun onPressDown() {
        restoreIfUnlockFading()
        pressedCount += 1
        unlockShownAtMillis = elapsedMillis()
    }

    /** 解锁按钮抬起：最后一指抬起后重新计时。 */
    fun onPressUp() {
        pressedCount = (pressedCount - 1).coerceAtLeast(0)
        if (pressedCount == 0) unlockShownAtMillis = elapsedMillis()
    }

    /** 距解锁按钮开始淡出的剩余毫秒；不在场、已在淡出或按住中时返回 -1。 */
    fun millisUntilUnlockFadeOut(): Long {
        if (!locked || !unlockVisible || unlockFading || unlockPressing) return -1L
        return (UNLOCK_FADE_START_MILLIS - (elapsedMillis() - unlockShownAtMillis)).coerceAtLeast(0L)
    }

    /** 距解锁按钮完全消失的剩余毫秒；不在淡出时返回 -1。 */
    fun millisUntilUnlockHidden(): Long {
        if (!unlockFading) return -1L
        return (UNLOCK_FADE_OUT_MILLIS - (elapsedMillis() - unlockFadeOutStartedAtMillis)).coerceAtLeast(0L)
    }

    /** 按钮在场且无操作满 [UNLOCK_FADE_START_MILLIS] 后开始淡出；按住期间不淡出。 */
    fun beginUnlockFadeOutIfExpired() {
        if (!locked || !unlockVisible || unlockFading || unlockPressing) return
        if (elapsedMillis() - unlockShownAtMillis >= UNLOCK_FADE_START_MILLIS) {
            unlockFading = true
            unlockFadeOutStartedAtMillis = elapsedMillis()
        }
    }

    /** 淡出满 [UNLOCK_FADE_OUT_MILLIS] 后移除解锁按钮。 */
    fun completeUnlockFadeOut() {
        if (!unlockFading) return
        if (elapsedMillis() - unlockFadeOutStartedAtMillis >= UNLOCK_FADE_OUT_MILLIS) {
            unlockVisible = false
            unlockFading = false
        }
    }

    /** 淡出中按下按钮撤销淡出并重新计时。 */
    private fun restoreIfUnlockFading() {
        if (!unlockFading) return
        unlockFading = false
        unlockShownAtMillis = elapsedMillis()
    }

    private fun showUnlock() {
        unlockShownAtMillis = elapsedMillis()
        unlockVisible = true
        unlockFading = false
    }
}

/**
 * 解锁按钮自动隐藏循环：按阶段等待到点，提前唤醒时重新评估并继续等。
 * 解锁、解锁按钮离开或按住时退出，等界面按状态变化重启循环。
 */
suspend fun runUnlockAutoHide(
    lock: PlayerLock,
    delayMillis: suspend (Long) -> Unit,
) {
    while (true) {
        if (!lock.locked || !lock.unlockVisible) return
        if (lock.unlockFading) {
            val remaining = lock.millisUntilUnlockHidden()
            if (remaining > 0L) delayMillis(remaining)
            lock.completeUnlockFadeOut()
        } else {
            val remaining = lock.millisUntilUnlockFadeOut()
            if (remaining < 0L) return
            if (remaining > 0L) delayMillis(remaining)
            lock.beginUnlockFadeOutIfExpired()
        }
    }
}
