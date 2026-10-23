package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerIconBarMetricsTest {

    @Test
    fun `三键热区各 62dp 宽、58dp 高，总宽 186dp`() {
        val metrics = playerIconBarMetrics(400.dp)

        assertEquals(62.dp, metrics.transportButtonWidth)
        assertEquals(58.dp, metrics.transportHeight)
        assertEquals(186.dp, metrics.transportWidth)
    }

    @Test
    fun `三键连续无间隙且播放键位于整条底栏正中`() {
        listOf(282.dp, 356.dp, 400.dp).forEach { width ->
            val metrics = playerIconBarMetrics(width)

            assertEquals(metrics.transportStart, metrics.previousStart)
            assertEquals(metrics.previousStart + metrics.transportButtonWidth, metrics.playPauseStart)
            assertEquals(metrics.playPauseStart + metrics.transportButtonWidth, metrics.nextStart)
            assertEquals(metrics.nextStart + metrics.transportButtonWidth, metrics.transportEnd)
            assertEquals(width.value / 2f, metrics.playPauseCenter.value, TOLERANCE)
        }
    }

    @Test
    fun `图标尺寸保持侧键 28dp、播放键 34dp`() {
        assertEquals(28.dp, PlayerTransportIconSize)
        assertEquals(34.dp, PlayerPlayPauseIconSize)
    }

    @Test
    fun `宽屏三键水平排布且右侧按钮不重叠`() {
        val metrics = playerIconBarMetrics(400.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(58.dp, metrics.barHeight)
        assertNoOverlap(metrics)
    }

    @Test
    fun `378dp 达到横排阈值`() {
        val metrics = playerIconBarMetrics(378.dp)

        assertFalse(metrics.rightStacked)
        assertNoOverlap(metrics)
    }

    @Test
    fun `377dp 低于横排阈值时右侧按钮上下排列`() {
        val metrics = playerIconBarMetrics(377.dp)

        assertTrue(metrics.rightStacked)
        assertEquals(PlayerSideTouchTarget * 2, metrics.barHeight)
        assertNoOverlap(metrics)
    }

    @Test
    fun `最小支持宽度 282dp 仍不重叠`() {
        assertEquals(282.dp, PlayerIconBarMinimumWidth)
        val metrics = playerIconBarMetrics(PlayerIconBarMinimumWidth)

        assertTrue(metrics.rightStacked)
        assertNoOverlap(metrics)
    }

    @Test
    fun `侧键默认图标尺寸保持 26dp`() {
        assertEquals(26.dp, PlayerSideIconSize)
    }

    private fun assertNoOverlap(metrics: PlayerIconBarMetrics) {
        assertFalse(metrics.overlapped)
        assertTrue(metrics.leftGroupEnd <= metrics.transportStart)
        assertTrue(metrics.transportEnd <= metrics.rightGroupStart)
        // 下一集按钮图标不得进入右侧按钮的点击区。
        assertTrue(metrics.nextIconEnd <= metrics.rightGroupStart)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
