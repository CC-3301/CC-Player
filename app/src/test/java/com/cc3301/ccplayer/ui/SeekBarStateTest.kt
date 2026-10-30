package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekBarStateTest {

    @Test
    fun `整行坐标按轨道范围映射到毫秒`() {
        assertEquals(0L, seekBarMillisAt(0f, 100f, 900f, 100_000L))
        assertEquals(0L, seekBarMillisAt(100f, 100f, 900f, 100_000L))
        assertEquals(50_000L, seekBarMillisAt(500f, 100f, 900f, 100_000L))
        assertEquals(100_000L, seekBarMillisAt(900f, 100f, 900f, 100_000L))
        assertEquals(100_000L, seekBarMillisAt(1_200f, 100f, 900f, 100_000L))
    }

    @Test
    fun `无效轨道或时长不映射`() {
        assertNull(seekBarMillisAt(500f, 900f, 100f, 100_000L))
        assertNull(seekBarMillisAt(500f, 100f, 100f, 100_000L))
        assertNull(seekBarMillisAt(500f, 100f, 900f, 0L))
        assertNull(seekBarMillisAt(500f, 100f, 900f, -1L))
    }

    @Test
    fun `轨道端点扣除时间区与滑块半径`() {
        // 两侧时间标签各 128px、16dp 间距、16dp 滑块在 2x 密度下的像素值。
        val bounds = seekBarTrackBounds(
            widthPx = 1_000f,
            leftTimeWidthPx = 128f,
            rightTimeWidthPx = 128f,
            spacingPx = 32f,
            thumbRadiusPx = 8f,
        )

        assertEquals(168f, bounds.start, 0.0001f)
        assertEquals(832f, bounds.end, 0.0001f)
    }

    @Test
    fun `时间标签跨位数后轨道端点仍映射到起止时间`() {
        val narrow = seekBarTrackBounds(1_000f, 76f, 76f, 32f, 8f)
        val wide = seekBarTrackBounds(1_000f, 92f, 92f, 32f, 8f)

        listOf(narrow, wide).forEach { bounds ->
            assertEquals(0L, seekBarMillisAt(bounds.start, bounds.start, bounds.end, 600_000L))
            assertEquals(600_000L, seekBarMillisAt(bounds.end, bounds.start, bounds.end, 600_000L))
        }
        assertEquals(16f, wide.start - narrow.start, 0.0001f)
    }

    @Test
    fun `左右标签宽度不等时按下首帧不跳位`() {
        val width = 1_000f
        val leftWidth = 76f
        val rightWidth = 92f
        val spacing = 32f
        val thumbRadius = 8f
        val oldBounds = seekBarTrackBounds(width, leftWidth, rightWidth, spacing, thumbRadius)
        val pressX = (oldBounds.start + oldBounds.end) / 2f

        assertEquals(
            300_000L,
            seekBarDownMillis(pressX, width, leftWidth, rightWidth, spacing, thumbRadius, 600_000L),
        )

        // 冻结后轨道左端右移；若用冻结轨道映射按下点会得到不同结果，说明首帧必须先按旧轨道计算。
        val frozenBounds = seekBarTrackBounds(width, 92f, 92f, spacing, thumbRadius)
        assertEquals(293_478L, seekBarMillisAt(pressX, frozenBounds.start, frozenBounds.end, 600_000L))
    }

    @Test
    fun `拖动期间时间标签取两侧较大宽度冻结轨道`() {
        val frozen = seekBarDragTimeWidth(leftTimeWidthPx = 76f, rightTimeWidthPx = 92f)
        val bounds = seekBarTrackBounds(1_000f, frozen, frozen, 32f, 8f)

        assertEquals(92f, frozen, 0.0001f)
        assertEquals(92f, seekBarDragTimeWidth(92f, 76f), 0.0001f)
        assertEquals(132f, bounds.start, 0.0001f)
        assertEquals(868f, bounds.end, 0.0001f)
    }

    @Test
    fun `极小宽度下轨道退化为不可拖动`() {
        val bounds = seekBarTrackBounds(
            widthPx = 100f,
            leftTimeWidthPx = 128f,
            rightTimeWidthPx = 128f,
            spacingPx = 32f,
            thumbRadiusPx = 8f,
        )

        assertEquals(50f, bounds.start, 0.0001f)
        assertEquals(50f, bounds.end, 0.0001f)
        assertNull(seekBarMillisAt(50f, bounds.start, bounds.end, 100_000L))
    }

    @Test
    fun `多指出现时取消拖动`() {
        assertFalse(isMultiPointerDrag(primaryId = 1L, pressedPointerIds = listOf(1L)))
        assertTrue(isMultiPointerDrag(primaryId = 1L, pressedPointerIds = listOf(1L, 2L)))
    }
}
