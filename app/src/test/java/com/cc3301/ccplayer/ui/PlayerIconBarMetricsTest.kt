package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerIconBarMetricsTest {

    @Test
    fun `320dp 内容宽度压缩三键并上下排列右侧按钮`() {
        val metrics = playerIconBarMetrics(320.dp)

        assertTrue(metrics.rightStacked)
        assertEquals(PlayerTransportButtonSize, metrics.previousNextSize)
        assertEquals(PlayerPlayPauseNarrowSize, metrics.playPauseSize)
        assertEquals(PlayerTransportNarrowGap, metrics.gap)
        assertEquals(PlayerSideTouchTarget, metrics.sideButtonSize)
        assertNoOverlap(metrics)
    }

    @Test
    fun `360dp 内容宽度压缩三键且右侧按钮横向排列`() {
        val metrics = playerIconBarMetrics(360.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(PlayerPlayPauseNarrowSize, metrics.playPauseSize)
        assertEquals(PlayerTransportNarrowGap, metrics.gap)
        assertNoOverlap(metrics)
    }

    @Test
    fun `宽屏使用 ADR 尺寸`() {
        val metrics = playerIconBarMetrics(400.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(PlayerPlayPauseButtonSize, metrics.playPauseSize)
        assertEquals(PlayerTransportGap, metrics.gap)
        assertNoOverlap(metrics)
    }

    @Test
    fun `三键整体大于两侧图标且播放键严格居中`() {
        listOf(320.dp, 360.dp, 400.dp).forEach { width ->
            val metrics = playerIconBarMetrics(width)

            assertTrue(PlayerTransportIconSize > PlayerSideIconSize)
            assertTrue(PlayerPlayPauseIconSize > PlayerSideIconSize)
            assertTrue(metrics.playPauseSize > metrics.sideButtonSize)
            assertEquals(
                width.value / 2f,
                (metrics.transportStart + metrics.transportWidth / 2f).value,
                TOLERANCE,
            )
        }
    }

    private fun assertNoOverlap(metrics: PlayerIconBarMetrics) {
        assertFalse(metrics.overlapped)
        assertTrue(metrics.leftGroupEnd <= metrics.transportStart)
        assertTrue(metrics.transportEnd <= metrics.rightGroupStart)
        // 下一集按钮图标不得进入字幕按钮的点击区。
        assertTrue(metrics.nextIconEnd <= metrics.rightGroupStart)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
