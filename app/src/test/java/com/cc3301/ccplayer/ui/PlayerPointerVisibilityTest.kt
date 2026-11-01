package com.cc3301.ccplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPointerVisibilityTest {

    private var now = 0L
    private val pointer = PlayerPointerVisibility(elapsedMillis = { now })
    private val oneX = Density(1f)
    private val threeX = Density(3f)

    @Test
    fun `指针位移死区为 5dp`() {
        assertEquals(5.dp, PlayerPointerMoveDeadZone)
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
        pointer.onPointerMove(Offset(3f, 4f), oneX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `抖动不重置隐藏计时`() {
        pointer.onPointerEnter(Offset.Zero)

        now = 2_000L
        pointer.onPointerMove(Offset(2f, 0f), oneX)

        now = 3_000L
        pointer.hideIfExpired()

        assertFalse(pointer.visible)
    }

    @Test
    fun `三倍屏下 5dp 死区等于 15 像素`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        pointer.onPointerMove(Offset(14f, 0f), threeX)
        assertFalse(pointer.visible)

        pointer.onPointerMove(Offset(16f, 0f), threeX)
        assertTrue(pointer.visible)
    }

    @Test
    fun `位移越过死区显示指针并重新计时`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 500L
        pointer.onPointerMove(Offset(0f, 6f), oneX)

        assertTrue(pointer.visible)
        assertEquals(500L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `隐藏后立即移动仍会重新计时并再次隐藏`() {
        pointer.onPointerEnter(Offset.Zero)

        now = 3_000L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)

        now = 3_016L
        pointer.onPointerMove(Offset(40f, 0f), oneX)
        assertTrue(pointer.visible)
        assertEquals(3_016L, pointer.lastActivityAtMillis)

        now = 6_015L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 6_016L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)
    }
}
