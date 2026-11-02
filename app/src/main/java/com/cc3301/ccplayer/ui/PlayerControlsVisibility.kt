package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 播放页控制层与解锁按钮的无操作自动隐藏时长，毫秒。 */
const val PLAYER_AUTO_HIDE_MILLIS = 5_000L

/** 控制层淡出的时长，毫秒；第 4 秒开始淡出、第 5 秒完全消失。 */
const val CONTROLS_FADE_OUT_MILLIS = 1_000L

/** 控制层无操作后开始淡出的时刻，毫秒。 */
const val CONTROLS_FADE_START_MILLIS = PLAYER_AUTO_HIDE_MILLIS - CONTROLS_FADE_OUT_MILLIS

/** 控制层无操作到完全消失的总时长，毫秒。 */
const val CONTROLS_AUTO_HIDE_MILLIS = PLAYER_AUTO_HIDE_MILLIS

/** 控制层显示时的淡入时长，毫秒。 */
const val CONTROLS_FADE_IN_MILLIS = 200L

/**
 * 播放页控制层的显隐与自动隐藏计时。
 *
 * 显示状态下 [CONTROLS_FADE_START_MILLIS] 内无操作即开始淡出，界面用
 * [CONTROLS_FADE_OUT_MILLIS] 淡完（无操作满 [CONTROLS_AUTO_HIDE_MILLIS] 完全消失）；
 * 任意操作重新计时；面板打开或控件按住期间不隐藏，关闭或抬起后重新计时（ADR-0025）。
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

    private var pressedCount by mutableIntStateOf(0)

    /** 有控件按住中：按下即重新计时，最后一指抬起前不自动隐藏。 */
    val pressing: Boolean
        get() = pressedCount > 0

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

    /** 控件按下：按操作重新计时并保持显示，抬起前不隐藏；多指同时按住按各自计数。 */
    fun onPressDown() {
        pressedCount += 1
        lastInteractionAtMillis = elapsedMillis()
    }

    /** 控件抬起：最后一指抬起后恢复计时，隐藏倒计时从这一刻重新起算。 */
    fun onPressUp() {
        pressedCount = (pressedCount - 1).coerceAtLeast(0)
        if (pressedCount == 0) lastInteractionAtMillis = elapsedMillis()
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

    /** 距开始淡出的剩余毫秒；不参与计时、[holdVisible] 或按住中时返回 -1。 */
    fun millisUntilAutoHide(holdVisible: Boolean = false): Long {
        if (holdVisible || !visible || panelOpen || pressing) return -1L
        val elapsed = elapsedMillis() - lastInteractionAtMillis
        return (CONTROLS_FADE_START_MILLIS - elapsed).coerceAtLeast(0L)
    }

    /** 显示状态下无操作满 [CONTROLS_FADE_START_MILLIS] 后转为隐藏，界面按 [CONTROLS_FADE_OUT_MILLIS] 淡出；面板打开、按住中或 [holdVisible] 时保持。 */
    fun hideIfExpired(holdVisible: Boolean = false) {
        if (holdVisible || !visible || panelOpen || pressing) return
        if (elapsedMillis() - lastInteractionAtMillis >= CONTROLS_FADE_START_MILLIS) {
            visible = false
        }
    }
}
