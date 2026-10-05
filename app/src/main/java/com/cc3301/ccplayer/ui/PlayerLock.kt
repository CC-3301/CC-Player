package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 解锁确认的有效期，毫秒。 */
const val UNLOCK_CONFIRM_WINDOW_MILLIS = 3_000L

/**
 * 播放页锁定状态：锁定时手势与控制条一并失效，只留解锁按钮。
 *
 * 解锁要点两次解锁按钮：锁定后第一次点进入确认态，确认期内再点一次才解锁；
 * 确认期从最后一次点击起算，恰好到边界视为超时（ADR-0014）。
 */
class PlayerLock(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var locked by mutableStateOf(false)
        private set

    /** 处于「再点一次解锁」的确认态。 */
    var unlockPending by mutableStateOf(false)
        private set

    private var pendingAtMillis = 0L

    /** 点解锁按钮；未锁定时上锁，锁定时按两次点击规则解锁。 */
    fun tapLockButton() {
        if (!locked) {
            locked = true
            clearPending()
            return
        }
        if (unlockPending && elapsedMillis() - pendingAtMillis < UNLOCK_CONFIRM_WINDOW_MILLIS) {
            locked = false
            clearPending()
            return
        }
        pendingAtMillis = elapsedMillis()
        unlockPending = true
    }

    /** 确认期超时：清掉确认态，下一次点击重新开始确认。 */
    fun expireUnlock() {
        if (unlockPending) clearPending()
    }

    private fun clearPending() {
        unlockPending = false
        pendingAtMillis = 0L
    }
}
