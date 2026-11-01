package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 播放页控制层与解锁按钮的无操作自动隐藏时长，毫秒。 */
const val PLAYER_AUTO_HIDE_MILLIS = 5_000L

/** 控制层无操作自动隐藏的时长，毫秒。 */
const val CONTROLS_AUTO_HIDE_MILLIS = PLAYER_AUTO_HIDE_MILLIS

/** 控制层显示与隐藏的淡入淡出时长，毫秒。 */
const val CONTROLS_FADE_MILLIS = 200L

/**
 * 播放页控制层的显隐与自动隐藏计时。
 *
 * 显示状态下 [CONTROLS_AUTO_HIDE_MILLIS] 内无操作即隐藏；任意操作重新计时；
 * 面板打开期间不隐藏，关闭后重新计时（ADR-0025）。
 *
 * 显示只由用户操作触发：点击画面 [toggle]、点击控件 [showByUserAction]、用户在面板里打开抽屉
 * 或菜单 [updatePanelOpen]；首次加载与切集不显示控制层。
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
        if (visible) visible = false else showByUserAction()
    }

    /** 点击画面或控件时显示控制层并重新计时。 */
    fun showByUserAction() {
        visible = true
        lastInteractionAtMillis = elapsedMillis()
    }

    /** 仅重新计时，不改显隐；手势只重置自动隐藏计时，不唤出控制层。 */
    fun resetTimer() {
        lastInteractionAtMillis = elapsedMillis()
    }

    /**
     * 首次加载与切集的阶段变化：显示状态重新计时，隐藏状态保持隐藏。
     * 控制层显隐只由点击决定，加载与切集不主动显示。
     */
    fun onLoadingPhaseChanged() {
        if (visible) resetTimer()
    }

    /** 面板显隐状态：用户打开面板时控制层一并发显示，打开期间暂停自动隐藏，关闭后重新计时。 */
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
