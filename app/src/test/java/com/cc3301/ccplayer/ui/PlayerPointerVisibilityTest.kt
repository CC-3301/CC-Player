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

    /** 进入播放页再把指针隐藏，作为「指针隐藏后鼠标在动」的起点。 */
    private fun hidePointer() {
        pointer.onPointerEnter()
        pointer.hide()
    }

    @Test
    fun `指针位移死区为 10dp`() {
        assertEquals(10.dp, PlayerPointerMoveDeadZone)
    }

    @Test
    fun `指针持续位移窗口为 150ms`() {
        assertEquals(150L, PLAYER_POINTER_SUSTAINED_MOVE_WINDOW_MILLIS)
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
    fun `上下起伏不唤出指针也不重置隐藏计时`() {
        hidePointer()

        // 幅度 6dp、半周期 100ms 的起伏：单程都不到死区。
        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        repeat(10) { index ->
            now += 100L
            pointer.onPointerMove(Offset(0f, if (index % 2 == 0) 6f else 0f), oneX)
        }

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `单程越过死区但持续不到窗口的抖动不唤出指针`() {
        hidePointer()

        // 幅度 12dp、半周期 20ms：每半程都越过死区，但每半程都不到窗口。
        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        repeat(10) { index ->
            now += 20L
            pointer.onPointerMove(Offset(0f, if (index % 2 == 0) 12f else 0f), oneX)
        }

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `来回抵消的位移不累计成同向净位移`() {
        hidePointer()

        // 上下各 8dp 反复：路径长度累计 24dp，但没有一次朝同一方向到 10dp。
        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        now = 500L
        pointer.onPointerMove(Offset(0f, 8f), oneX)
        now = 700L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        now = 900L
        pointer.onPointerMove(Offset(0f, 8f), oneX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `同向净位移累计越过死区且持续满窗口时唤出指针`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        now = 130L
        pointer.onPointerMove(Offset(0f, 6f), oneX)

        // 同向累计已越过死区，但持续只有 30ms，指针不出现也不重新计时。
        now = 160L
        pointer.onPointerMove(Offset(0f, 12f), oneX)
        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)

        now = 280L
        pointer.onPointerMove(Offset(0f, 24f), oneX)

        assertTrue(pointer.visible)
        assertEquals(280L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `持续移动满窗口才唤出指针`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        now = 110L
        pointer.onPointerMove(Offset(0f, 1f), oneX)

        // 同向净位移早已越过死区，持续不满 150ms 时仍不出现。
        now = 259L
        pointer.onPointerMove(Offset(0f, 24f), oneX)
        assertFalse(pointer.visible)

        now = 260L
        pointer.onPointerMove(Offset(0f, 25f), oneX)

        assertTrue(pointer.visible)
        assertEquals(260L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `一次越过死区的位移不算持续移动`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        now = 200L
        pointer.onPointerMove(Offset(0f, 20f), oneX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `进入播放页后单次越界不唤出指针`() {
        hidePointer()

        now = 400L
        pointer.onPointerMove(Offset(0f, 20f), oneX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `改换方向的位移从新方向重新起算`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        now = 110L
        pointer.onPointerMove(Offset(0f, 8f), oneX)

        // 先向上 8dp，再向下 8dp：向下不从向上的累计里扣，而是从新方向重新起算。
        now = 260L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        assertFalse(pointer.visible)

        now = 270L
        pointer.onPointerMove(Offset(0f, -1f), oneX)
        now = 420L
        pointer.onPointerMove(Offset(0f, -10f), oneX)

        assertTrue(pointer.visible)
        assertEquals(420L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `三倍屏下同向净位移不到 30 像素不唤出指针`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), threeX)
        now = 110L
        pointer.onPointerMove(Offset(0f, 1f), threeX)
        now = 300L
        pointer.onPointerMove(Offset(0f, 29f), threeX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `三倍屏下同向净位移满 30 像素且持续满窗口时唤出指针`() {
        hidePointer()

        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), threeX)
        now = 110L
        pointer.onPointerMove(Offset(0f, 1f), threeX)
        now = 260L
        pointer.onPointerMove(Offset(0f, 31f), threeX)

        assertTrue(pointer.visible)
        assertEquals(260L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `控制层可见时指针直接跟随鼠标`() {
        pointer.onPointerEnter()
        pointer.hide()
        pointerFollowsBars(barsPresent = true, pointer = pointer)
        assertTrue(pointer.visible)

        // 远小于死区的一次移动也直接跟随，并重新计时。
        now = 2_900L
        pointer.onPointerMove(Offset(0.5f, 0.5f), oneX)

        assertTrue(pointer.visible)
        assertEquals(2_900L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `控制层隐藏后指针回到位移判据`() {
        pointer.onPointerEnter()
        pointerFollowsBars(barsPresent = true, pointer = pointer)
        pointerFollowsBars(barsPresent = false, pointer = pointer)
        assertFalse(pointer.visible)

        now = 100L
        pointer.onPointerMove(Offset(0f, 0f), oneX)
        now = 400L
        pointer.onPointerMove(Offset(0f, 4f), oneX)

        assertFalse(pointer.visible)
        assertEquals(0L, pointer.lastActivityAtMillis)
    }

    @Test
    fun `隐藏后持续移动重新计时并再次隐藏`() {
        pointer.onPointerEnter()

        now = 3_000L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)

        now = 3_020L
        pointer.onPointerMove(Offset(0f, 2f), oneX)
        now = 3_040L
        pointer.onPointerMove(Offset(0f, 4f), oneX)
        now = 3_200L
        pointer.onPointerMove(Offset(0f, 14f), oneX)
        assertTrue(pointer.visible)
        assertEquals(3_200L, pointer.lastActivityAtMillis)

        now = 6_199L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 6_200L
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
