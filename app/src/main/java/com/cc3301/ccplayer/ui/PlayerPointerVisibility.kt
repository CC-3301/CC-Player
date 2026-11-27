package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType

/**
 * 指针是否跟随鼠标：任一类播放 UI 在场。
 * 控制层、锁 UI、播放列表与缓冲 UI 任一在场，指针就跟着鼠标走；全部离场指针立刻隐藏。
 */
fun playerPointerUiPresent(
    barsPresent: Boolean,
    lockUiPresent: Boolean,
    playlistPresent: Boolean,
    bufferingPresent: Boolean,
): Boolean = barsPresent || lockUiPresent || playlistPresent || bufferingPresent

/**
 * 这次指针事件是否让指针重新出现并重新计时：只有鼠标悬停移动算。
 * 滚轮、按住拖动、右键与中键都不重置隐藏计时，也不唤出控制层。
 */
fun pointerHoverMoveResetsHideTimer(
    eventType: PointerEventType,
    pointerType: PointerType,
    anyButtonPressed: Boolean,
): Boolean = eventType == PointerEventType.Move &&
    pointerType == PointerType.Mouse &&
    !anyButtonPressed

/**
 * 播放页指针可见性：随播放 UI 瞬时显隐，不参与淡出。
 *
 * 播放 UI 在场时指针出现并跟随鼠标，鼠标静止满 [PLAYER_POINTER_HIDE_DELAY_MILLIS] 也隐藏；
 * 播放 UI 全部离场时立刻隐藏。隐藏后鼠标悬停移动即重新出现，不设位移判据。
 */
class PlayerPointerVisibility(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var visible by mutableStateOf(true)
        private set

    /** 最近一次让指针重新计时的时刻，毫秒。 */
    private var lastActivityAtMillis = elapsedMillis()

    /** 播放 UI 在场：指针出现与跟随鼠标的前提。 */
    private var uiPresent = false

    /** 播放 UI 在场状态变化：在场即出现并重新计时，全部离场立刻隐藏。 */
    fun onUiPresenceChanged(present: Boolean) {
        uiPresent = present
        if (present) show() else hide()
    }

    /** 鼠标悬停移动：指针出现并重新计时。 */
    fun onPointerMove() {
        if (!uiPresent) return
        show()
    }

    /** 左键唤醒：指针与控制层一起出现并重新计时。 */
    fun show() {
        visible = true
        lastActivityAtMillis = elapsedMillis()
    }

    /** 距自动隐藏的剩余毫秒；指针已隐藏时返回 -1。 */
    fun millisUntilHide(): Long {
        if (!visible) return -1L
        return (PLAYER_POINTER_HIDE_DELAY_MILLIS - (elapsedMillis() - lastActivityAtMillis)).coerceAtLeast(0L)
    }

    /** 静止满 [PLAYER_POINTER_HIDE_DELAY_MILLIS] 后隐藏指针。 */
    fun hideIfExpired() {
        if (!visible) return
        if (elapsedMillis() - lastActivityAtMillis >= PLAYER_POINTER_HIDE_DELAY_MILLIS) hide()
    }

    private fun hide() {
        visible = false
    }
}

/**
 * 指针隐藏后的第一次左键只用于唤醒：这一次完整手势及其双击窗口都不产生播放动作。
 *
 * 由鼠标左键的按下与抬起驱动：指针隐藏时的左键按下才算唤醒，指针可见时的按下是普通点击；
 * 唤醒手势抬起后进入 [doubleTapWindowMillis] 窗口，窗口内再次按下同样整次吞掉，但窗口不重新起算。
 * 触摸、滚轮、右键与中键不参与这套判定。
 */
class PlayerPointerWake(
    private val doubleTapWindowMillis: Long = DOUBLE_TAP_WINDOW_MILLIS,
) {
    /** 唤醒手势进行中：从隐藏态左键按下到抬起。 */
    private var waking = false

    /** 双击窗口截止时刻；没有窗口时为 [Long.MIN_VALUE]。 */
    private var swallowUntilMillis = Long.MIN_VALUE

    /**
     * 鼠标左键按下：返回这次按下是否只用于唤醒。只有指针隐藏时的左键按下算唤醒；
     * 指针可见时的按下交给控件执行，不进入唤醒手势。
     */
    fun press(pointerVisible: Boolean, primaryPressed: Boolean): Boolean {
        if (pointerVisible || !primaryPressed) return false
        waking = true
        return true
    }

    /** 左键抬起：从此刻起算这次唤醒手势的双击窗口。 */
    fun onRelease(nowMillis: Long) {
        if (!waking) return
        waking = false
        swallowUntilMillis = nowMillis + doubleTapWindowMillis
    }

    /** 这次按下的完整手势是否只用于唤醒。 */
    fun swallows(nowMillis: Long): Boolean = waking || nowMillis <= swallowUntilMillis
}
