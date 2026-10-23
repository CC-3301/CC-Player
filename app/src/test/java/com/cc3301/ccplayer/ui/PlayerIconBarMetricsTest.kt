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
        assertEquals(58.dp, metrics.transportButtonHeight)
        assertEquals(186.dp, metrics.transportTotalWidth)
    }

    @Test
    fun `图标条实际依赖的侧键尺寸与普通高度`() {
        val metrics = playerIconBarMetrics(400.dp)

        assertEquals(48.dp, PlayerSideTouchTarget)
        assertEquals(58.dp, metrics.barHeight)
    }

    @Test
    fun `360dp 时右侧字幕与播放列表上下排列`() {
        val metrics = playerIconBarMetrics(360.dp)

        assertTrue(metrics.rightStacked)
        assertEquals(PlayerSideTouchTarget * 2, metrics.barHeight)
    }

    @Test
    fun `377dp 低于横排阈值时右侧按钮上下排列`() {
        val metrics = playerIconBarMetrics(377.dp)

        assertTrue(metrics.rightStacked)
        assertEquals(PlayerSideTouchTarget * 2, metrics.barHeight)
    }

    @Test
    fun `378dp 达到横排阈值`() {
        val metrics = playerIconBarMetrics(378.dp)

        assertFalse(metrics.rightStacked)
        assertEquals(58.dp, metrics.barHeight)
    }

    @Test
    fun `横排阈值与最小支持宽度`() {
        assertEquals(378.dp, PlayerIconBarHorizontalThreshold)
        assertEquals(282.dp, PlayerIconBarMinimumWidth)
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
