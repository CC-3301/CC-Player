package com.cc3301.ccplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPointerVisibilityTest {

    private var now = 0L
    private val pointer = PlayerPointerVisibility(elapsedMillis = { now })
    private val controls = PlayerControlsVisibility(elapsedMillis = { now })

    /** 以 5 像素代表 5dp 死区换算后的像素值，只用于纯逻辑判定。 */
    private val deadZonePx = 5f

    @Test
    fun `指针位移死区为 5dp`() {
        assertEquals(5f, PlayerPointerMoveDeadZone.value, 0f)
    }

    @Test
    fun `指针静止三秒后隐藏`() {
        now = 2_999L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 3_000L
        pointer.hideIfExpired()

        assertFalse(pointer.visible)
        assertEquals(-1L, pointer.millisUntilHide())
    }

    @Test
    fun `位移不超过死区算抖动不显示指针`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 100L
        pointer.onPointerMove(Offset(3f, 4f), deadZonePx)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `抖动不重置隐藏计时`() {
        pointer.onPointerEnter(Offset.Zero)

        now = 2_000L
        pointer.onPointerMove(Offset(2f, 0f), deadZonePx)

        now = 3_000L
        pointer.hideIfExpired()

        assertFalse(pointer.visible)
    }

    @Test
    fun `位移越过死区显示指针并重新计时`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 500L
        pointer.onPointerMove(Offset(0f, 6f), deadZonePx)

        assertTrue(pointer.visible)
        assertEquals(500L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `控制层隐藏时移动只唤出指针`() {
        controls.toggle()
        assertFalse(controls.visible)

        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 1_000L
        pointer.onPointerMove(Offset(20f, 0f), deadZonePx)

        assertTrue(pointer.visible)
        assertFalse(controls.visible)
    }

    @Test
    fun `控制层隐藏时指针一起隐藏`() {
        pointer.onPointerEnter(Offset.Zero)
        assertTrue(pointer.visible)

        pointer.followControls(controlsVisible = false)

        assertFalse(pointer.visible)
    }

    @Test
    fun `控制层出现时指针一起出现`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        pointer.followControls(controlsVisible = true)

        assertTrue(pointer.visible)
    }
}
