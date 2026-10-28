package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 双击提示：按侧累计、3 秒窗口与涟漪触发，全部用假时钟驱动。 */
class PlayerDoubleTapFeedbackTest {

    private var now = 0L
    private val feedback = PlayerDoubleTapFeedback { now }

    @Test
    fun `首次双击显示对应侧的累计秒数`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)

        assertEquals(10_000L, feedback.left?.accumulatedMillis)
        assertNull(feedback.right)
    }

    @Test
    fun `三秒内同侧继续双击累计`() {
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)
        now = 2_999L
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)

        assertEquals(20_000L, feedback.right?.accumulatedMillis)
    }

    @Test
    fun `窗口从最后一次双击重新计时`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        now = 2_000L
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        now = 4_500L
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)

        assertEquals(30_000L, feedback.left?.accumulatedMillis)
    }

    @Test
    fun `超过三秒未双击则归零`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        now = 3_001L
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)

        assertEquals(10_000L, feedback.left?.accumulatedMillis)
    }

    @Test
    fun `左右两侧各自累计互不影响`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)
        now = 1_000L
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)

        assertEquals(20_000L, feedback.left?.accumulatedMillis)
        assertEquals(10_000L, feedback.right?.accumulatedMillis)
    }

    @Test
    fun `每次双击产生一条对应侧的涟漪`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        val first = feedback.ripples.single()
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)
        val second = feedback.ripples.last()

        assertEquals(DoubleTapSide.Left, first.side)
        assertEquals(DoubleTapSide.Right, second.side)
        assertEquals(1L, first.id)
        assertEquals(2L, second.id)
    }

    @Test
    fun `连点两侧的涟漪同时保留`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)

        assertEquals(listOf(DoubleTapSide.Left, DoubleTapSide.Right), feedback.ripples.map { it.side })
        assertEquals(2, feedback.ripples.size)
    }

    @Test
    fun `涟漪动画结束后只移除对应条目`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)

        feedback.removeRipple(1L)

        assertEquals(listOf(2L), feedback.ripples.map { it.id })
    }

    @Test
    fun `不产生涟漪时只累计秒数`() {
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L, ripple = false)

        assertEquals(10_000L, feedback.right?.accumulatedMillis)
        assertEquals(emptyList<DoubleTapRipple>(), feedback.ripples)
    }

    @Test
    fun `累计使用夹取后的实际位移`() {
        feedback.onDoubleTap(DoubleTapSide.Right, 3_000L)

        assertEquals(3_000L, feedback.right?.accumulatedMillis)
    }

    @Test
    fun `一侧到期时另一侧仍保留`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        now = 1_000L
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)
        now = 3_001L

        feedback.expire()

        assertNull(feedback.left)
        assertEquals(10_000L, feedback.right?.accumulatedMillis)
    }

    @Test
    fun `切集重置清空提示与涟漪`() {
        feedback.onDoubleTap(DoubleTapSide.Left, -10_000L)
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)

        feedback.reset()

        assertNull(feedback.left)
        assertNull(feedback.right)
        assertEquals(emptyList<DoubleTapRipple>(), feedback.ripples)
    }

    @Test
    fun `三秒后提示自动清除`() {
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)
        now = 3_000L

        assertEquals(1L, feedback.millisUntilExpire())

        now = 3_001L
        feedback.expire()

        assertNull(feedback.left)
        assertNull(feedback.right)
        assertNull(feedback.millisUntilExpire())
    }
}
