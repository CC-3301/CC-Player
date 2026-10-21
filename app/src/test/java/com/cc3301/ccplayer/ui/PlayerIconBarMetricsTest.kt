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
        assertEquals(PlayerSideTouchTarget * 2, metrics.barHeight)
        assertNoOverlap(metrics)
    }

    @Test
    fun `360dp 内容宽度压缩三键且右侧按钮横向排列`() {
        val metrics = playerIconBarMetrics(360.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(PlayerPlayPauseNarrowSize, metrics.playPauseSize)
        assertEquals(PlayerTransportNarrowGap, metrics.gap)
        assertEquals(PlayerPlayPauseNarrowSize, metrics.barHeight)
        assertNoOverlap(metrics)
    }

    @Test
    fun `宽屏使用 ADR 尺寸`() {
        val metrics = playerIconBarMetrics(400.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(PlayerPlayPauseButtonSize, metrics.playPauseSize)
        assertEquals(PlayerTransportGap, metrics.gap)
        assertEquals(PlayerPlayPauseButtonSize, metrics.barHeight)
        assertNoOverlap(metrics)
    }

    @Test
    fun `355dp 低于窄屏横排阈值时上下排列`() {
        val metrics = playerIconBarMetrics(355.dp)

        assertTrue(metrics.rightStacked)
        assertNoOverlap(metrics)
    }

    @Test
    fun `356dp 达到窄屏横排阈值`() {
        val metrics = playerIconBarMetrics(356.dp)

        assertFalse(metrics.rightStacked)
        assertNoOverlap(metrics)
    }

    @Test
    fun `377dp 仍为窄屏横排`() {
        val metrics = playerIconBarMetrics(377.dp)

        assertFalse(metrics.rightStacked)
        assertNoOverlap(metrics)
    }

    @Test
    fun `378dp 达到宽屏阈值`() {
        val metrics = playerIconBarMetrics(378.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(PlayerPlayPauseButtonSize, metrics.playPauseSize)
        assertEquals(PlayerTransportGap, metrics.gap)
        assertNoOverlap(metrics)
    }

    @Test
    fun `窄屏横排阈值附近保持原排布`() {
        val staysStacked = playerIconBarMetrics(377.dp, previousRightStacked = true)
        assertTrue(staysStacked.rightStacked)
        assertNoOverlap(staysStacked)

        val staysHorizontal = playerIconBarMetrics(356.dp, previousRightStacked = false)
        assertFalse(staysHorizontal.rightStacked)
        assertNoOverlap(staysHorizontal)

        // 低于窄屏横排下限时无法保持横排，退回上下排列。
        val forcedStacked = playerIconBarMetrics(355.dp, previousRightStacked = false)
        assertTrue(forcedStacked.rightStacked)
        assertNoOverlap(forcedStacked)
    }

    @Test
    fun `最小支持宽度 260dp 仍不重叠`() {
        assertEquals(260.dp, PlayerIconBarMinimumWidth)
        val metrics = playerIconBarMetrics(PlayerIconBarMinimumWidth)

        assertTrue(metrics.rightStacked)
        assertNoOverlap(metrics)
    }

    @Test
    fun `侧键默认图标尺寸保持 26dp`() {
        assertEquals(26.dp, PlayerSideIconSize)
    }

    @Test
    fun `三键图标大于两侧图标且播放键严格居中`() {
        listOf(320.dp, 360.dp, 400.dp).forEach { width ->
            val metrics = playerIconBarMetrics(width)

            assertTrue(PlayerTransportIconSize > PlayerSideIconSize)
            assertTrue(PlayerPlayPauseIconSize > PlayerSideIconSize)
            assertTrue(metrics.previousNextSize >= metrics.sideButtonSize)
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
