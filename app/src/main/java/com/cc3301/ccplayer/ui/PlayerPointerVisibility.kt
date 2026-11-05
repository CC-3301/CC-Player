package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density

/**
 * 播放页鼠标指针的显隐与自动隐藏计时。
 *
 * 指针在 [PLAYER_POINTER_HIDE_DELAY_MILLIS] 内没有有效移动即隐藏。位移不超过
 * [PlayerPointerMoveDeadZone]（按当前密度换算成像素）的抖动不算移动；越过死区的位移还要
 * 距上一次指针移动不超过 [PLAYER_POINTER_CONTINUOUS_MOVE_WINDOW_MILLIS] 才算有效移动，
 * 孤立的越界既不显示指针，也不重置隐藏计时，也不推进死区锚点。
 * 指针随顶/底栏的实际可见性显隐，见 [pointerFollowsBars]。
 */
class PlayerPointerVisibility(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var visible by mutableStateOf(true)
        private set

    /** 最近一次有效移动或显式显示的时刻；只被计时协程读取，不参与重组。 */
    var lastActivityAtMillis: Long = elapsedMillis()
        private set

    /** 位移死区的锚点：最后一次越过死区的位置。 */
    private var anchor: Offset? = null

    /** 上一次指针移动事件的时刻；每次移动都刷新，未越过死区的也算。 */
    private var lastMoveEventAtMillis: Long? = null

    /** 指针进入播放页：指针出现，锚点与移动事件清空，进入后的第一次移动只记锚点。 */
    fun onPointerEnter() {
        anchor = null
        lastMoveEventAtMillis = null
        show()
    }

    /**
     * 指针移动：先刷新移动事件时刻，再以死区锚点算位移。
     * 越过死区且距上一次移动不超过连续位移窗口才算真的在动，此时指针出现并重设锚点；
     * 孤立的越界不改变锚点，回弹仍落在死区内，不会二次触发。
     */
    fun onPointerMove(position: Offset, density: Density) {
        val deadZonePx = with(density) { PlayerPointerMoveDeadZone.toPx() }
        val now = elapsedMillis()
        val previousEventAtMillis = lastMoveEventAtMillis
        lastMoveEventAtMillis = now

        val previous = anchor
        if (previous == null) {
            anchor = position
            return
        }
        if ((position - previous).getDistance() <= deadZonePx) return
        val continuous = previousEventAtMillis?.let {
            now - it <= PLAYER_POINTER_CONTINUOUS_MOVE_WINDOW_MILLIS
        } ?: false
        if (!continuous) return
        anchor = position
        show()
    }

    /** 显示指针并重新计时。 */
    fun show() {
        visible = true
        lastActivityAtMillis = elapsedMillis()
    }

    /** 隐藏指针。 */
    fun hide() {
        visible = false
    }

    /** 距自动隐藏的剩余毫秒；指针已隐藏时返回 -1。 */
    fun millisUntilHide(): Long {
        if (!visible) return -1L
        return (PLAYER_POINTER_HIDE_DELAY_MILLIS - (elapsedMillis() - lastActivityAtMillis)).coerceAtLeast(0L)
    }

    /** 静止满 [PLAYER_POINTER_HIDE_DELAY_MILLIS] 后隐藏指针。 */
    fun hideIfExpired() {
        if (!visible) return
        if (elapsedMillis() - lastActivityAtMillis >= PLAYER_POINTER_HIDE_DELAY_MILLIS) {
            visible = false
        }
    }
}

/**
 * 指针自动隐藏循环：指针隐藏期间挂起等它重新出现，出现后按剩余时间等待，到点隐藏。
 * 循环常驻不退出，指针被重新唤起后计时自然继续，不存在计时中断的空档。
 */
suspend fun runPointerAutoHide(
    pointer: PlayerPointerVisibility,
    awaitVisible: suspend () -> Unit,
    delayMillis: suspend (Long) -> Unit,
) {
    while (true) {
        awaitVisible()
        val remaining = pointer.millisUntilHide()
        if (remaining > 0L) delayMillis(remaining)
        pointer.hideIfExpired()
    }
}

/**
 * 指针跟随控制层的在场状态：在场时指针一并出现，离开时一并隐藏。
 * 只改指针，不碰控制层，控件显隐仍只由用户操作决定。
 */
fun pointerFollowsBars(barsPresent: Boolean, pointer: PlayerPointerVisibility) {
    if (barsPresent) pointer.show() else pointer.hide()
}
