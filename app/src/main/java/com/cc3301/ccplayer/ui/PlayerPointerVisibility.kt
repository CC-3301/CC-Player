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
 * 指针在 [PLAYER_POINTER_HIDE_DELAY_MILLIS] 内没有有效移动即隐藏。有效移动要求同向净位移达到
 * [PlayerPointerMoveDeadZone]（按当前密度换算成像素）且这段位移持续
 * [PLAYER_POINTER_SUSTAINED_MOVE_WINDOW_MILLIS] 以上：反向的位移把累计值减回去，来回抵消的
 * 抖动不计数；改换方向的位移从新方向重新起算。
 * 指针随顶/底栏的实际可见性显隐，见 [pointerFollowsBars]；栏在场时指针直接跟随鼠标，不做判定。
 */
class PlayerPointerVisibility(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var visible by mutableStateOf(true)
        private set

    /** 顶/底栏在场：指针直接跟随鼠标，不做位移判定。由 [pointerFollowsBars] 维护。 */
    var followsPointerDirectly: Boolean = false
        internal set

    /** 最近一次显示指针或径直跟随的时刻；只被计时协程读取，不参与重组。 */
    var lastActivityAtMillis: Long = elapsedMillis()
        private set

    /** 上一次移动事件的位置；进入播放页后清空，下一次移动只记位置。 */
    private var lastPosition: Offset? = null

    /** 当前同向净位移的方向；为 null 表示没有正在累计的位移。 */
    private var moveDirection: Offset? = null

    /** 沿 [moveDirection] 的净位移，反向的位移会把它减小。 */
    private var moveDistance: Float = 0f

    /** 当前同向净位移的起始时刻。 */
    private var moveStartedAtMillis: Long = 0L

    /** 指针进入播放页：指针出现，位移状态清空，进入后的第一次移动只记位置。 */
    fun onPointerEnter() {
        lastPosition = null
        resetMove()
        show()
    }

    /**
     * 指针移动：同向净位移越过死区并持续满窗口才算真的在动，此时指针出现并重新起算。
     * 栏在场时不做判定，直接显示并重新计时。
     */
    fun onPointerMove(position: Offset, density: Density) {
        val now = elapsedMillis()
        val previous = lastPosition
        lastPosition = position
        if (followsPointerDirectly) {
            resetMove()
            show()
            return
        }
        if (previous == null) return

        val delta = position - previous
        val length = delta.getDistance()
        if (length <= 0f) return

        val direction = moveDirection
        val advance = direction?.let { delta.dot(it) } ?: 0f
        if (direction == null || advance <= 0f) {
            // 没有正在累计的位移，或这次位移没有沿当前方向推进：从这次位移重新起算。
            moveDirection = delta / length
            moveDistance = length
            moveStartedAtMillis = now
        } else {
            moveDistance += advance
        }

        val deadZonePx = with(density) { PlayerPointerMoveDeadZone.toPx() }
        if (moveDistance < deadZonePx) return
        if (now - moveStartedAtMillis < PLAYER_POINTER_SUSTAINED_MOVE_WINDOW_MILLIS) return
        resetMove()
        show()
    }

    /** 显示指针并重新计时。 */
    fun show() {
        visible = true
        lastActivityAtMillis = elapsedMillis()
    }

    /** 隐藏指针；隐藏后重新按位移判据判定。 */
    fun hide() {
        visible = false
        resetMove()
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

    /** 清空同向净位移的累计。 */
    private fun resetMove() {
        moveDirection = null
        moveDistance = 0f
        moveStartedAtMillis = 0L
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
    pointer.followsPointerDirectly = barsPresent
    if (barsPresent) pointer.show() else pointer.hide()
}
