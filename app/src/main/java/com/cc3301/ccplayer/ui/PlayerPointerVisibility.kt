package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density

/**
 * 播放页鼠标指针的显隐与自动隐藏计时。
 *
 * 指针在 [PLAYER_POINTER_HIDE_DELAY_MILLIS] 内没有有效移动即隐藏；位移不超过
 * [PlayerPointerMoveDeadZone]（按当前密度换算成像素）的抖动不算移动。
 * 指针随顶/底栏的实际可见性显隐，见 [pointerFollowsBars]。
 */
class PlayerPointerVisibility(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var visible by mutableStateOf(true)
        private set

    /** 最近一次有效移动或显式显示的时刻；作为自动隐藏计时的键参与重组。 */
    var lastActivityAtMillis by mutableLongStateOf(elapsedMillis())
        private set

    /** 位移死区的锚点：最后一次有效移动的位置。 */
    private var anchor: Offset? = null

    /** 指针进入播放页：指针出现，进入位置作为位移死区锚点。 */
    fun onPointerEnter(position: Offset) {
        anchor = position
        show()
    }

    /** 指针移动：以死区锚点算位移，越过死区才算移动并重设锚点；抖动不改变任何状态。 */
    fun onPointerMove(position: Offset, density: Density) {
        val deadZonePx = with(density) { PlayerPointerMoveDeadZone.toPx() }
        val previous = anchor
        if (previous == null || (position - previous).getDistance() > deadZonePx) {
            anchor = position
            show()
        }
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
 * 指针跟随顶/底栏的实际可见性：栏可见时指针一并出现，栏隐藏时一并隐藏。
 * 只改指针，不碰控制层，控件显隐仍只由用户操作决定。
 */
fun pointerFollowsBars(barsVisible: Boolean, pointer: PlayerPointerVisibility) {
    if (barsVisible) pointer.show() else pointer.hide()
}
