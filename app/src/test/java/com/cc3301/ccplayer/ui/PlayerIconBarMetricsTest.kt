package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerIconBarMetricsTest {

    @Test
    fun `宽屏三键热区各 62dp 宽、58dp 高，总宽 186dp`() {
        val metrics = playerIconBarMetrics(400.dp)

        assertEquals(62.dp, metrics.transportButtonWidth)
        assertEquals(58.dp, metrics.transportButtonHeight)
        assertEquals(186.dp, metrics.transportTotalWidth)
        assertFalse(metrics.rightStacked)
    }

    @Test
    fun `图标条实际依赖的侧键尺寸与普通高度`() {
        val metrics = playerIconBarMetrics(400.dp)

        assertEquals(48.dp, PlayerSideTouchTarget)
        assertEquals(58.dp, metrics.barHeight)
    }

    @Test
    fun `360dp 竖屏三键收窄到 52dp 且字幕与播放列表同排`() {
        val metrics = playerIconBarMetrics(360.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(52.dp, metrics.transportButtonWidth)
        assertEquals(156.dp, metrics.transportTotalWidth)
        assertEquals(58.dp, metrics.barHeight)
        // 收窄后三键居中，左右各剩 102dp，容得下 96dp 的两个侧键。
        assertEquals(102.dp, (360.dp - metrics.transportTotalWidth) / 2)
    }

    @Test
    fun `377dp 不足 378dp 时收窄到 52dp 并保持同排`() {
        val metrics = playerIconBarMetrics(377.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(52.dp, metrics.transportButtonWidth)
        assertEquals(58.dp, metrics.barHeight)
    }

    @Test
    fun `378dp 达到横排阈值时不再收窄`() {
        val metrics = playerIconBarMetrics(378.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(62.dp, metrics.transportButtonWidth)
    }

    @Test
    fun `348dp 是收窄后横排的边界`() {
        val metrics = playerIconBarMetrics(348.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(52.dp, metrics.transportButtonWidth)
    }

    @Test
    fun `347dp 收窄后仍放不下右侧并排时上下排列`() {
        val metrics = playerIconBarMetrics(347.dp)

        assertTrue(metrics.rightStacked)
        assertEquals(52.dp, metrics.transportButtonWidth)
        assertEquals(PlayerSideTouchTarget * 2, metrics.barHeight)
    }

    @Test
    fun `横排阈值与最小支持宽度`() {
        assertEquals(378.dp, PlayerIconBarHorizontalThreshold)
        assertEquals(348.dp, PlayerIconBarNarrowHorizontalThreshold)
        assertEquals(252.dp, PlayerIconBarMinimumWidth)
        assertTrue(playerIconBarMetrics(PlayerIconBarMinimumWidth).rightStacked)
    }

    @Test
    fun `图标尺寸保持侧键 28dp、播放键 34dp`() {
        assertEquals(28.dp, PlayerTransportIconSize)
        assertEquals(34.dp, PlayerPlayPauseIconSize)
    }

    @Test
    fun `侧键默认图标尺寸保持 26dp`() {
        assertEquals(26.dp, PlayerSideIconSize)
    }
}
