package com.cc3301.ccplayer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

        // 位移正好等于死区不算移动，判据是严格大于。
        pointer.onPointerMove(Offset(15f, 0f), threeX)
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

    @Test
    fun `指针隐藏后重新唤起，计时循环仍会再次隐藏`() = runTest {
        val looped = PlayerPointerVisibility(elapsedMillis = { testScheduler.currentTime })
        val visibleSignal = Channel<Unit>(Channel.UNLIMITED)
        val loop = launch {
            runPointerAutoHide(
                pointer = looped,
                awaitVisible = { visibleSignal.receive() },
                delayMillis = { delay(it) },
            )
        }

        // 第一次出现后静止满 3 秒隐藏。
        visibleSignal.trySend(Unit)
        advanceTimeBy(2_999L)
        runCurrent()
        assertTrue(looped.visible)

        advanceTimeBy(1L)
        runCurrent()
        assertFalse(looped.visible)

        // 隐藏 16 毫秒后再移动：指针出现，并从此刻重新计时。
        advanceTimeBy(16L)
        looped.onPointerEnter(Offset.Zero)
        visibleSignal.trySend(Unit)
        runCurrent()
        assertTrue(looped.visible)

        advanceTimeBy(3_000L)
        runCurrent()
        assertFalse(looped.visible)

        loop.cancel()
    }
}
