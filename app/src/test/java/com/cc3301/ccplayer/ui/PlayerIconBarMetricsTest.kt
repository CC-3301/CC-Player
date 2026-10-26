package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerIconBarMetricsTest {

    @Test
    fun `宽屏三键各 62dp、58dp 高，不缩放`() {
        val metrics = playerIconBarMetrics(400.dp)

        assertEquals(62.dp, metrics.transportButtonWidth)
        assertEquals(58.dp, metrics.transportButtonHeight)
        assertEquals(186.dp, metrics.transportTotalWidth)
        assertEquals(1f, metrics.scale, TOLERANCE)
        assertEquals(48.dp, metrics.sideTouchTarget)
        assertButtonsFit(400.dp, metrics)
    }

    @Test
    fun `360dp 竖屏三键收窄到 52dp，两侧仍放得下、不缩放`() {
        val metrics = playerIconBarMetrics(360.dp)

        assertEquals(52.dp, metrics.transportButtonWidth)
        assertEquals(156.dp, metrics.transportTotalWidth)
        assertEquals(58.dp, metrics.transportButtonHeight)
        assertEquals(1f, metrics.scale, TOLERANCE)
        assertEquals(48.dp, metrics.sideTouchTarget)
        // 三键居中后两侧通道各 102dp，容得下右侧并排的两个 48dp 侧键。
        assertEquals(102.dp, metrics.sideChannelWidth)
        assertButtonsFit(360.dp, metrics)
    }

    @Test
    fun `377dp 不足 378dp 时收窄到 52dp`() {
        val metrics = playerIconBarMetrics(377.dp)

        assertEquals(52.dp, metrics.transportButtonWidth)
        assertEquals(1f, metrics.scale, TOLERANCE)
        assertButtonsFit(377.dp, metrics)
    }

    @Test
    fun `378dp 用 62dp 三键且通道恰好放得下`() {
        val metrics = playerIconBarMetrics(378.dp)

        assertEquals(62.dp, metrics.transportButtonWidth)
        assertEquals(96.dp, metrics.sideChannelWidth)
        assertEquals(1f, metrics.scale, TOLERANCE)
        assertButtonsFit(378.dp, metrics)
    }

    @Test
    fun `通道放不下时缩放系数等于通道可用宽度与所需宽度的比值`() {
        val metrics = playerIconBarMetrics(347.dp)

        assertTrue(metrics.scale < 1f)
        assertEquals(
            metrics.sideChannelWidth / metrics.sideChannelRequiredWidth,
            metrics.scale,
            TOLERANCE,
        )
        assertButtonsFit(347.dp, metrics)
    }

    @Test
    fun `300dp 等比缩小到四分之三，六个按钮仍同线、播放键居中且不重叠`() {
        val metrics = playerIconBarMetrics(300.dp)

        assertEquals(0.75f, metrics.scale, TOLERANCE)
        assertEquals(39.dp, metrics.transportButtonWidth)
        assertEquals(43.5.dp, metrics.transportButtonHeight)
        assertEquals(36.dp, metrics.sideTouchTarget)
        assertButtonsFit(300.dp, metrics)
    }

    @Test
    fun `更窄时继续等比缩小，不设硬性下限`() {
        val metrics = playerIconBarMetrics(200.dp)

        assertTrue(metrics.scale > 0f)
        assertTrue(metrics.scale < 0.25f)
        assertButtonsFit(200.dp, metrics)
    }

    @Test
    fun `图标与热区使用同一缩放系数`() {
        val metrics = playerIconBarMetrics(300.dp)

        assertEquals(PlayerSideIconSize * metrics.scale, metrics.sideIconSize)
        assertEquals(PlayerTransportIconSize * metrics.scale, metrics.transportIconSize)
        assertEquals(PlayerPlayPauseIconSize * metrics.scale, metrics.playPauseIconSize)
    }

    /**
     * 三键组居中、两侧通道各自容得下自己的按钮：
     * 左侧锁定键不越过三键，右侧字幕与播放列表并排不越过三键。
     */
    private fun assertButtonsFit(width: Dp, metrics: PlayerIconBarMetrics) {
        val availableWidth = width.value
        val transportWidth = metrics.transportTotalWidth.value
        val side = metrics.sideTouchTarget.value
        val transportStart = (availableWidth - transportWidth) / 2f
        val transportEnd = transportStart + transportWidth
        val rightGroupStart = availableWidth - side * 2f

        assertTrue("左侧通道容不下锁定键", transportStart + TOLERANCE >= side)
        assertTrue("右侧两键压到三键", rightGroupStart + TOLERANCE >= transportEnd)
        assertEquals(
            "播放键不在中线",
            availableWidth / 2f,
            transportStart + transportWidth / 2f,
            TOLERANCE,
        )
        // 侧键不超过三键高度，六个按钮因此落在同一条水平线。
        assertTrue(metrics.sideTouchTarget <= metrics.transportButtonHeight)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
