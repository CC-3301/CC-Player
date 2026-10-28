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
    fun `位置到达目标后清除保持值`() {
        // 正向跳转：位置到达目标即清除，越过目标同样清除。
        assertFalse(shouldKeepSeekPreview(45_000L, 45_000L, 10_000L))
        assertFalse(shouldKeepSeekPreview(50_000L, 45_000L, 10_000L))
        // 反向跳转：位置降到目标即清除。
        assertFalse(shouldKeepSeekPreview(45_000L, 45_000L, 90_000L))
        assertFalse(shouldKeepSeekPreview(44_722L, 45_000L, 90_000L))
    }

    @Test
    fun `位置仍在起点与目标之间时保留`() {
        // 落定 seek 从目标前的关键帧起解，位置先报告目标之前，此时不能清。
        assertTrue(shouldKeepSeekPreview(44_722L, 45_000L, 10_000L))
        assertTrue(shouldKeepSeekPreview(20_000L, 45_000L, 10_000L))
        // 反向跳转还没降下来时同样保留。
        assertTrue(shouldKeepSeekPreview(80_000L, 45_000L, 90_000L))
        assertTrue(shouldKeepSeekPreview(46_600L, 45_000L, 90_000L))
    }

    @Test
    fun `小幅跳转在起点附近来回一帧也不提前清除`() {
        // 反向小幅拖动：起点只比目标大 200ms，首帧与下一帧 tick 都落在起点与目标之间。
        assertTrue(shouldKeepSeekPreview(45_200L, 45_000L, 45_200L))
        assertTrue(shouldKeepSeekPreview(45_190L, 45_000L, 45_200L))
        assertTrue(shouldKeepSeekPreview(45_010L, 45_000L, 45_200L))
        // 正向小幅拖动同理。
        assertTrue(shouldKeepSeekPreview(45_000L, 45_200L, 45_000L))
        assertTrue(shouldKeepSeekPreview(45_080L, 45_200L, 45_000L))
    }

    @Test
    fun `目标就是起点时立即清除`() {
        assertFalse(shouldKeepSeekPreview(45_000L, 45_000L, 45_000L))
    }

    @Test
    fun `多指出现时取消拖动`() {
        assertFalse(isMultiPointerDrag(primaryId = 1L, pressedPointerIds = listOf(1L)))
        assertTrue(isMultiPointerDrag(primaryId = 1L, pressedPointerIds = listOf(1L, 2L)))
    }
}
