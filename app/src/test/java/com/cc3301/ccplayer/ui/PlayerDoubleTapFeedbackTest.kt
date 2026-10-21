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
        val first = feedback.ripple
        feedback.onDoubleTap(DoubleTapSide.Right, 10_000L)
        val second = feedback.ripple

        assertEquals(DoubleTapSide.Left, first?.side)
        assertEquals(DoubleTapSide.Right, second?.side)
        assertEquals(1L, first?.id)
        assertEquals(2L, second?.id)
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
