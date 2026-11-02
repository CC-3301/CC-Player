package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 播放页控制层与解锁按钮无操作到完全隐藏的总时长，毫秒。 */
const val PLAYER_AUTO_HIDE_MILLIS = 5_000L

/** 控制层淡出的时长，毫秒；第 4 秒开始淡出、第 5 秒完全消失。 */
const val CONTROLS_FADE_OUT_MILLIS = 1_000L

/** 控制层无操作后开始淡出的时刻，毫秒。 */
const val CONTROLS_FADE_START_MILLIS = PLAYER_AUTO_HIDE_MILLIS - CONTROLS_FADE_OUT_MILLIS

/** 控制层显示时的淡入时长，毫秒。 */
const val CONTROLS_FADE_IN_MILLIS = 200L

/** 控制层退场动画：时长与淡出窗口同源，界面直接使用这里给出的时长。 */
fun controlsFadeOutSpec(fadeOutMillis: Long): TweenSpec<Float> = tween(fadeOutMillis.toInt())

/** 控制层的显隐阶段。 */
enum class ControlsPhase {
    /** 可见：正常显示；无操作满 [CONTROLS_FADE_START_MILLIS] 转入淡出。 */
    Shown,

    /** 淡出中：仍在场上、仍可点，透明度走完 [CONTROLS_FADE_OUT_MILLIS] 后转入已隐藏；任何操作立刻回到可见。 */
    Fading,

    /** 已隐藏：移除子树、隐藏指针、清理涟漪。 */
    Hidden,
}

/**
 * 播放页控制层的显隐与自动隐藏计时。
 *
 * 可见态无操作满 [CONTROLS_FADE_START_MILLIS] 进入淡出，界面用 [CONTROLS_FADE_OUT_MILLIS]
 * 淡完，无操作满 [PLAYER_AUTO_HIDE_MILLIS] 完全隐藏；淡出期间任何操作撤销淡出并重新计时。
 * 面板打开或控件按住期间不隐藏，关闭或抬起后重新计时（ADR-0025）。
 *
 * 显示只由用户操作触发：点击画面 [toggle]、点击控件 [showByUserAction]、用户在面板里打开抽屉
 * 或菜单 [updatePanelOpen]；首次加载与切集不显示控制层。
 */
class PlayerControlsVisibility(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
    val fadeOutMillis: Long = CONTROLS_FADE_OUT_MILLIS,
) {
    var phase by mutableStateOf(ControlsPhase.Shown)
        private set

    /** 完全显示：界面据此决定进场与退场动画目标。 */
    val visible: Boolean get() = phase == ControlsPhase.Shown

    /** 淡出中：仍在场上，操作可撤销。 */
    val fading: Boolean get() = phase == ControlsPhase.Fading

    /** 已完全隐藏：移除子树、隐藏指针、清理涟漪。 */
    val hidden: Boolean get() = phase == ControlsPhase.Hidden

    var panelOpen by mutableStateOf(false)
        private set

    /** 最近一次操作时刻，变化时驱动界面重启隐藏计时。 */
    var lastInteractionAtMillis by mutableLongStateOf(elapsedMillis())
        private set

    /** 本次淡出的开始时刻，变化时驱动界面等待淡出结束。 */
    var fadeOutStartedAtMillis by mutableLongStateOf(0L)
        private set

    private var pressedCount by mutableIntStateOf(0)

    /** 有控件按住中：按下即重新计时，最后一指抬起前不自动隐藏。 */
    val pressing: Boolean
        get() = pressedCount > 0

    /** 点击画面：显示时开始淡出，淡出或隐藏时显示并重新计时。 */
    fun toggle() {
        if (visible) beginFadeOut() else showByUserAction()
    }

    /** 点击画面或控件时显示控制层并重新计时。 */
    fun showByUserAction() {
        phase = ControlsPhase.Shown
        lastInteractionAtMillis = elapsedMillis()
    }

    /** 只重新计时，不改显隐；淡出中操作撤销淡出，已隐藏时手势不唤出控制层。 */
    fun resetTimer() {
        restoreIfFading()
        lastInteractionAtMillis = elapsedMillis()
    }

    /** 控件按下：淡出中撤销淡出，按操作重新计时并保持显示，抬起前不隐藏；多指同时按住按各自计数。 */
    fun onPressDown() {
        restoreIfFading()
        pressedCount += 1
        lastInteractionAtMillis = elapsedMillis()
    }

    /** 控件抬起：最后一指抬起后恢复计时，隐藏倒计时从这一刻重新起算。 */
    fun onPressUp() {
        pressedCount = (pressedCount - 1).coerceAtLeast(0)
        if (pressedCount == 0) lastInteractionAtMillis = elapsedMillis()
    }

    /**
     * 首次加载与切集的阶段变化：可见态重新计时，淡出与隐藏态保持原状。
     * 控制层显隐只由点击决定，加载与切集不主动显示。
     */
    fun onLoadingPhaseChanged() {
        if (visible) resetTimer()
    }

    /** 面板显隐状态：用户打开面板时控制层一并发显示，打开期间暂停自动隐藏，关闭后重新计时。 */
    fun updatePanelOpen(open: Boolean) {
        panelOpen = open
        if (open) {
            showByUserAction()
        } else {
            lastInteractionAtMillis = elapsedMillis()
        }
    }

    /** 距开始淡出的剩余毫秒；不在可见态、面板打开、按住中或 [holdVisible] 时返回 -1。 */
    fun millisUntilFadeOut(holdVisible: Boolean = false): Long {
        if (holdVisible || !visible || panelOpen || pressing) return -1L
        return (CONTROLS_FADE_START_MILLIS - (elapsedMillis() - lastInteractionAtMillis)).coerceAtLeast(0L)
    }

    /** 距完全隐藏的剩余毫秒；不在淡出态时返回 -1。 */
    fun millisUntilHidden(): Long {
        if (!fading) return -1L
        return (fadeOutMillis - (elapsedMillis() - fadeOutStartedAtMillis)).coerceAtLeast(0L)
    }

    /** 可见态无操作满 [CONTROLS_FADE_START_MILLIS] 后开始淡出；面板打开、按住中或 [holdVisible] 时保持。 */
    fun beginFadeOutIfExpired(holdVisible: Boolean = false) {
        if (holdVisible || !visible || panelOpen || pressing) return
        if (elapsedMillis() - lastInteractionAtMillis >= CONTROLS_FADE_START_MILLIS) beginFadeOut()
    }

    /** 淡出满 [fadeOutMillis] 后进入已隐藏。 */
    fun completeFadeOut() {
        if (!fading) return
        if (elapsedMillis() - fadeOutStartedAtMillis >= fadeOutMillis) phase = ControlsPhase.Hidden
    }

    /** 淡出中的操作撤销淡出并重新计时。 */
    private fun restoreIfFading() {
        if (!fading) return
        phase = ControlsPhase.Shown
        lastInteractionAtMillis = elapsedMillis()
    }

    private fun beginFadeOut() {
        phase = ControlsPhase.Fading
        fadeOutStartedAtMillis = elapsedMillis()
        lastInteractionAtMillis = elapsedMillis()
    }
}
