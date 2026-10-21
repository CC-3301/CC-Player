package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 控制层无操作自动隐藏的时长，毫秒。 */
const val CONTROLS_AUTO_HIDE_MILLIS = 3_000L

/**
 * 播放页控制层的显隐与自动隐藏计时。
 *
 * 显示状态下 [CONTROLS_AUTO_HIDE_MILLIS] 内无操作即隐藏；任意操作重新计时；
 * 面板打开期间不隐藏，关闭后重新计时（ADR-0025）。
 */
class PlayerControlsVisibility(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var visible by mutableStateOf(true)
        private set

    var panelOpen by mutableStateOf(false)
        private set

    /** 最近一次操作时刻，变化时驱动界面重启隐藏计时。 */
    var lastInteractionAtMillis by mutableLongStateOf(elapsedMillis())
        private set

    /** 点击画面：显示时隐藏，隐藏时显示并重新计时。 */
    fun toggle() {
        if (visible) visible = false else onInteraction()
    }

    /** 任意操作：显示控制层并重新计时。 */
    fun onInteraction() {
        visible = true
        lastInteractionAtMillis = elapsedMillis()
    }

    /** 面板显隐状态；打开期间暂停自动隐藏，关闭后重新计时。 */
    fun updatePanelOpen(open: Boolean) {
        panelOpen = open
        if (open) {
            visible = true
        } else {
            lastInteractionAtMillis = elapsedMillis()
        }
    }

    /** 距自动隐藏的剩余毫秒；不参与计时或 [holdVisible] 为真时返回 -1。 */
    fun millisUntilAutoHide(holdVisible: Boolean = false): Long {
        if (holdVisible || !visible || panelOpen) return -1L
        val elapsed = elapsedMillis() - lastInteractionAtMillis
        return (CONTROLS_AUTO_HIDE_MILLIS - elapsed).coerceAtLeast(0L)
    }

    /** 显示状态下无操作满 [CONTROLS_AUTO_HIDE_MILLIS] 后隐藏；面板打开或 [holdVisible] 时保持。 */
    fun hideIfExpired(holdVisible: Boolean = false) {
        if (holdVisible || !visible || panelOpen) return
        if (elapsedMillis() - lastInteractionAtMillis >= CONTROLS_AUTO_HIDE_MILLIS) {
            visible = false
        }
    }
}
