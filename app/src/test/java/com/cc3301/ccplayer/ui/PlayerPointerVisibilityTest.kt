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
    fun `指针位移死区为 10dp`() {
        assertEquals(10.dp, PlayerPointerMoveDeadZone)
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
    fun `三倍屏下 10dp 死区等于 30 像素`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        // 位移正好等于死区不算移动，判据是严格大于。
        now = 100L
        pointer.onPointerMove(Offset(29f, 0f), threeX)
        now = 110L
        pointer.onPointerMove(Offset(30f, 0f), threeX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `连续位移唤出指针并重新计时`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 400L
        pointer.onPointerMove(Offset(0f, 20f), oneX)
        now = 500L
        pointer.onPointerMove(Offset(0f, 40f), oneX)

        assertTrue(pointer.visible)
        assertEquals(500L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `单次越过死区的位移不唤出指针`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 400L
        pointer.onPointerMove(Offset(0f, 20f), oneX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `连续位移窗口内的两次位移才唤出指针`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 100L
        pointer.onPointerMove(Offset(0f, 20f), oneX)
        now = 350L
        pointer.onPointerMove(Offset(0f, 40f), oneX)

        assertTrue(pointer.visible)
        assertEquals(350L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `间隔超过连续位移窗口的位移不唤出指针`() {
        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 100L
        pointer.onPointerMove(Offset(0f, 20f), oneX)
        now = 351L
        pointer.onPointerMove(Offset(0f, 40f), oneX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `连续位移唤出指针时控制层保持隐藏`() {
        val controls = PlayerControlsVisibility(elapsedMillis = { now })
        controls.toggle()
        assertTrue(controls.hidden)

        pointer.onPointerEnter(Offset.Zero)
        pointer.hide()

        now = 400L
        pointer.onPointerMove(Offset(0f, 20f), oneX)
        now = 500L
        pointer.onPointerMove(Offset(0f, 40f), oneX)

        assertTrue(pointer.visible)
        assertTrue(controls.hidden)
    }

    @Test
    fun `隐藏后立即移动仍会重新计时并再次隐藏`() {
        pointer.onPointerEnter(Offset.Zero)

        now = 3_000L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)

        now = 3_016L
        pointer.onPointerMove(Offset(40f, 0f), oneX)
        now = 3_100L
        pointer.onPointerMove(Offset(60f, 0f), oneX)
        assertTrue(pointer.visible)
        assertEquals(3_100L, pointer.lastActivityAtMillis)

        now = 6_099L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 6_100L
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
