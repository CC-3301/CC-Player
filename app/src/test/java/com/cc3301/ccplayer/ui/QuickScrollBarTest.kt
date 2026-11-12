package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快速滚动条的形态、几何换算与显隐计时。 */
class QuickScrollBarTest {

    private var now = 0L
    private val bar = QuickScrollBarVisibility(elapsedMillis = { now })
    private val oneX = Density(1f)

    private fun geometry(
        itemCount: Int,
        rowSize: Int,
        visibleItemCount: Int,
        trackHeight: Float = 1_000f,
    ) = QuickScrollBarGeometry(
        itemCount = itemCount,
        rowSize = rowSize,
        visibleItemCount = visibleItemCount,
        trackHeight = trackHeight,
        minThumbHeight = with(oneX) { QuickScrollBarMinLength.toPx() },
    )

    @Test
    fun `滚动条宽 6dp 最短 64dp 热区 32dp`() {
        assertEquals(6.dp, QuickScrollBarWidth)
        assertEquals(64.dp, QuickScrollBarMinLength)
        assertEquals(32.dp, QuickScrollBarTouchBandWidth)
    }

    @Test
    fun `静止 1200ms 后淡出 淡入 120ms 淡出 200ms`() {
        assertEquals(1_200L, QUICK_SCROLL_BAR_IDLE_MILLIS)
        assertEquals(120, QUICK_SCROLL_BAR_FADE_IN_MILLIS)
        assertEquals(200, QUICK_SCROLL_BAR_FADE_OUT_MILLIS)
    }

    @Test
    fun `进入时隐藏 滚动后出现并从最后一次滚动计时`() {
        assertFalse(bar.visible)
        bar.onScroll()
        assertTrue(bar.visible)
        now = 700L
        bar.onScroll()
        now = 1_899L
        bar.hideIfExpired()
        assertTrue(bar.visible)
        now = 1_900L
        bar.hideIfExpired()
        assertFalse(bar.visible)
    }

    @Test
    fun `按住期间不淡出 松手后从松手重新计时`() {
        bar.onScroll()
        now = 500L
        bar.onPress()
        now = 9_000L
        bar.hideIfExpired()
        assertTrue(bar.visible)
        bar.onRelease()
        now = 10_199L
        bar.hideIfExpired()
        assertTrue(bar.visible)
        now = 10_200L
        bar.hideIfExpired()
        assertFalse(bar.visible)
    }

    @Test
    fun `拖动保持出现并从最后一次拖动计时`() {
        bar.onScroll()
        now = 1_100L
        bar.onDrag()
        now = 2_299L
        bar.hideIfExpired()
        assertTrue(bar.visible)
        now = 2_300L
        bar.hideIfExpired()
        assertFalse(bar.visible)
    }

    @Test
    fun `条长按可见行数占比例 不足最短长度时取最短`() {
        val short = geometry(200, 1, 10, trackHeight = 600f)
        assertEquals(200, short.totalRows)
        assertEquals(10, short.visibleRows)
        assertEquals(64f, short.thumbHeight, 0.01f)

        val long = geometry(200, 1, 100, trackHeight = 600f)
        assertEquals(300f, long.thumbHeight, 0.01f)
    }

    @Test
    fun `条位按首行在可滚动行程中的比例`() {
        val geometry = geometry(20, 1, 10)
        assertEquals(500f, geometry.thumbHeight, 0.01f)
        assertEquals(0f, geometry.thumbTop(0), 0.01f)
        assertEquals(250f, geometry.thumbTop(5), 0.01f)
        assertEquals(500f, geometry.thumbTop(10), 0.01f)
    }

    @Test
    fun `按下位置按条长换算成行 网格落在目标行第一项`() {
        val grid = geometry(200, 3, 12)
        assertEquals(67, grid.totalRows)
        assertEquals(4, grid.visibleRows)
        assertEquals(64f, grid.thumbHeight, 0.01f)
        assertEquals(0, grid.rowAt(32f))
        assertEquals(63, grid.rowAt(1_000f))
        assertEquals(189, grid.itemAt(1_000f))
        assertEquals(96, grid.itemAt(500f))
    }

    @Test
    fun `列表拖到底停在末屏首项`() {
        val list = geometry(200, 1, 10)
        assertEquals(190, list.rowAt(1_000f))
        assertEquals(190, list.itemAt(1_000f))
        assertEquals(0, list.rowAt(0f))
    }

    @Test
    fun `全部可见时条铺满轨道且不移动`() {
        val geometry = geometry(8, 1, 8)
        assertEquals(1_000f, geometry.thumbHeight, 0.01f)
        assertEquals(0f, geometry.thumbTop(0), 0.01f)
        assertEquals(0f, geometry.thumbTop(7), 0.01f)
        assertEquals(0, geometry.itemAt(900f))
    }
}
