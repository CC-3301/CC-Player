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

    /** 进入播放页、指针停在 [position]，再把指针隐藏：之后的移动都按这个起点判定。 */
    private fun hidePointer(position: Offset = Offset.Zero) {
        pointer.onPointerEnter()
        pointer.onPointerMove(position, oneX, pressed = false)
        pointer.hide()
    }

    @Test
    fun `指针位移死区为 100dp`() {
        assertEquals(100.dp, PlayerPointerMoveDeadZone)
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
    fun `起点取指针隐藏那一刻的坐标`() {
        hidePointer(position = Offset(40f, 40f))

        now = 100L
        pointer.onPointerMove(Offset(40f, 139f), oneX, pressed = false)
        assertFalse(pointer.visible)

        now = 200L
        pointer.onPointerMove(Offset(40f, 140f), oneX, pressed = false)
        assertTrue(pointer.visible)
        assertEquals(200L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `斜向移动按直线距离判定`() {
        hidePointer()

        // (60, 79) 到起点的直线距离 99.2dp，不到死区。
        now = 100L
        pointer.onPointerMove(Offset(60f, 79f), oneX, pressed = false)
        assertFalse(pointer.visible)

        // (60, 80) 正好 100dp。
        now = 200L
        pointer.onPointerMove(Offset(60f, 80f), oneX, pressed = false)
        assertTrue(pointer.visible)
        assertEquals(200L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `抖动既不唤出指针也不重置隐藏计时`() {
        hidePointer()

        // 幅度 6dp、半周期 100ms 的抖动：每次都不到直线距离死区。
        now = 100L
        repeat(20) { index ->
            now += 100L
            pointer.onPointerMove(Offset(if (index % 2 == 0) 6f else 0f, 0f), oneX, pressed = false)
        }

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `来回摆动按路径累加也不算移动`() {
        hidePointer()

        // 每程 60dp、来回 8 次，路径累计 480dp，但离起点始终不超过 60dp。
        now = 100L
        repeat(8) { index ->
            now += 100L
            pointer.onPointerMove(Offset(if (index % 2 == 0) 60f else 0f, 0f), oneX, pressed = false)
        }

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `停顿不重算起点`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(60f, 0f), oneX, pressed = false)

        // 停 10 秒再接着走：起点仍是隐藏那一刻的位置，不清零重算。
        now = 10_100L
        pointer.onPointerMove(Offset(99f, 0f), oneX, pressed = false)
        assertFalse(pointer.visible)

        now = 10_200L
        pointer.onPointerMove(Offset(100f, 0f), oneX, pressed = false)
        assertTrue(pointer.visible)
        assertEquals(10_200L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `反向移动不清空已走的直线距离`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(80f, 0f), oneX, pressed = false)
        now = 200L
        pointer.onPointerMove(Offset(30f, 0f), oneX, pressed = false)
        assertFalse(pointer.visible)

        now = 300L
        pointer.onPointerMove(Offset(101f, 0f), oneX, pressed = false)
        assertTrue(pointer.visible)
        assertEquals(300L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `按住拖动既不唤出指针也不重置隐藏计时`() {
        hidePointer()

        now = 100L
        repeat(5) { index ->
            now += 100L
            pointer.onPointerMove(Offset(0f, 60f * (index + 1)), oneX, pressed = true)
        }

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `三倍屏下直线距离满三百像素才唤出指针`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(0f, 299f), threeX, pressed = false)
        assertFalse(pointer.visible)

        now = 200L
        pointer.onPointerMove(Offset(0f, 300f), threeX, pressed = false)
        assertTrue(pointer.visible)
        assertEquals(200L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `可见时按移动重新计时`() {
        pointer.onPointerEnter()
        assertTrue(pointer.visible)

        now = 1_000L
        pointer.onPointerMove(Offset(1f, 1f), oneX, pressed = false)
        assertEquals(1_000L, pointer.lastActivityAtMillis)

        now = 3_999L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 4_000L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)
    }

    @Test
    fun `隐藏后走满死区唤出指针，随后静止三秒再次隐藏`() {
        pointer.onPointerEnter()

        now = 3_000L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)

        // 没有移动过，隐藏时没有坐标：第一次移动只作起点，不唤出指针。
        now = 3_020L
        pointer.onPointerMove(Offset(0f, 2f), oneX, pressed = false)
        assertFalse(pointer.visible)

        now = 3_040L
        pointer.onPointerMove(Offset(0f, 102f), oneX, pressed = false)
        assertTrue(pointer.visible)
        assertEquals(3_040L, pointer.lastActivityAtMillis)

        now = 6_039L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 6_040L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)
    }

    @Test
    fun `控制层可见时指针直接跟随鼠标`() {
        pointer.onPointerEnter()
        pointer.hide()
        pointerFollowsBars(barsPresent = true, pointer = pointer)
        assertTrue(pointer.visible)

        // 远小于死区的一次移动也直接跟随，并重新计时。
        now = 2_900L
        pointer.onPointerMove(Offset(0.5f, 0.5f), oneX, pressed = false)

        assertTrue(pointer.visible)
        assertEquals(2_900L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `控制层隐藏后指针回到直线距离判据`() {
        pointer.onPointerEnter()
        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX, pressed = false)
        pointerFollowsBars(barsPresent = true, pointer = pointer)
        pointerFollowsBars(barsPresent = false, pointer = pointer)
        assertFalse(pointer.visible)

        now = 200L
        pointer.onPointerMove(Offset(0f, 99f), oneX, pressed = false)
        assertFalse(pointer.visible)

        now = 300L
        pointer.onPointerMove(Offset(0f, 100f), oneX, pressed = false)
        assertTrue(pointer.visible)
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

        // 隐藏 16 毫秒后再进入播放页：指针出现，并从此刻重新计时。
        advanceTimeBy(16L)
        looped.onPointerEnter()
        visibleSignal.trySend(Unit)
        runCurrent()
        assertTrue(looped.visible)

        advanceTimeBy(3_000L)
        runCurrent()
        assertFalse(looped.visible)

        loop.cancel()
    }
}
