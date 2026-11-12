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
 * 指针静止满 [PLAYER_POINTER_HIDE_DELAY_MILLIS] 即隐藏。隐藏期间鼠标移动是否算有效移动只看直线
 * 距离：起点取指针隐藏那一刻的坐标，起点到当前指针位置的直线距离达到
 * [PlayerPointerMoveDeadZone]（按当前密度换算成像素）才让指针出现并更新起点。不按路径长度累加，
 * 不设停顿重算，也不设反向清零，起点只在指针隐藏与判定有效时更新。
 * 按住拖动期间既不唤出指针，也不重置隐藏计时；控制层在场时指针直接跟随鼠标，不走这条判据，见
 * [pointerFollowsBars]。
 */
class PlayerPointerVisibility(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var visible by mutableStateOf(true)
        private set

    /** 顶/底栏在场：指针直接跟随鼠标，不做位移判定。 */
    private var followsPointerDirectly = false

    /** 最近一次显示指针或径直跟随的时刻；只被计时协程读取，不参与重组。 */
    var lastActivityAtMillis: Long = elapsedMillis()
        private set

    /** 最近一次指针位置；隐藏时取它作为判定的起点。 */
    private var lastPosition: Offset? = null

    /** 判定的起点：指针隐藏那一刻的坐标，只在隐藏与判定有效时更新。 */
    private var startPosition: Offset? = null

    /** 指针进入播放页：指针出现，起点清空，下一次隐藏重新取起点。 */
    fun onPointerEnter() {
        lastPosition = null
        startPosition = null
        show()
    }

    /**
     * 指针移动：隐藏期间直线距离达到死区才算有效移动，指针出现并更新起点；可见时按这次移动重新
     * 计时。按住拖动不参与判定，也不重置计时。栏在场时不做判定，直接跟随。
     */
    fun onPointerMove(position: Offset, density: Density, pressed: Boolean) {
        lastPosition = position

        if (followsPointerDirectly) {
            show()
            return
        }
        if (pressed) return
        if (visible) {
            show()
            return
        }

        val start = startPosition ?: position.also { startPosition = it }
        val deadZonePx = with(density) { PlayerPointerMoveDeadZone.toPx() }
        if ((position - start).getDistance() < deadZonePx) return

        startPosition = position
        show()
    }

    /** 显示指针并重新计时。 */
    fun show() {
        visible = true
        lastActivityAtMillis = elapsedMillis()
    }

    /** 隐藏指针，起点取隐藏这一刻的坐标。 */
    fun hide() {
        visible = false
        startPosition = lastPosition
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
            hide()
        }
    }

    /** 控制层在场状态变化：在场时指针一并出现并直接跟随鼠标，离场时一并隐藏、回到位移判据。 */
    fun onControlLayerPresenceChanged(present: Boolean) {
        followsPointerDirectly = present
        if (present) show() else hide()
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
 * 指针跟随控制层的在场状态：在场时指针一并出现并直接跟随鼠标，离开时一并隐藏、回到位移判据。
 * 只改指针，不碰控制层，控件显隐仍只由用户操作决定。
 */
fun pointerFollowsBars(barsPresent: Boolean, pointer: PlayerPointerVisibility) {
    pointer.onControlLayerPresenceChanged(barsPresent)
}
