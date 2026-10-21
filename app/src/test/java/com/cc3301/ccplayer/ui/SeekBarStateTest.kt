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
        // 64dp 时间区、8dp 间距、16dp 滑块在 2x 密度下的像素值。
        val bounds = seekBarTrackBounds(
            widthPx = 1_000f,
            leftTimeWidthPx = 128f,
            rightTimeWidthPx = 128f,
            spacingPx = 16f,
            thumbRadiusPx = 8f,
        )

        assertEquals(152f, bounds.start, 0.0001f)
        assertEquals(848f, bounds.end, 0.0001f)
    }

    @Test
    fun `极小宽度下轨道退化为不可拖动`() {
        val bounds = seekBarTrackBounds(
            widthPx = 100f,
            leftTimeWidthPx = 128f,
            rightTimeWidthPx = 128f,
            spacingPx = 16f,
            thumbRadiusPx = 8f,
        )

        assertEquals(50f, bounds.start, 0.0001f)
        assertEquals(50f, bounds.end, 0.0001f)
        assertNull(seekBarMillisAt(50f, bounds.start, bounds.end, 100_000L))
    }

    @Test
    fun `末次预览在位置追平或越过目标后清除`() {
        assertTrue(shouldKeepSeekPreview(20_000L, 45_000L, 10_000L, mediaChanged = false, timedOut = false))
        assertFalse(shouldKeepSeekPreview(44_500L, 45_000L, 10_000L, mediaChanged = false, timedOut = false))
        assertFalse(shouldKeepSeekPreview(50_000L, 45_000L, 10_000L, mediaChanged = false, timedOut = false))
        assertTrue(shouldKeepSeekPreview(80_000L, 45_000L, 90_000L, mediaChanged = false, timedOut = false))
        assertFalse(shouldKeepSeekPreview(40_000L, 45_000L, 90_000L, mediaChanged = false, timedOut = false))
        assertFalse(shouldKeepSeekPreview(20_000L, null, 10_000L, mediaChanged = false, timedOut = false))
    }

    @Test
    fun `末次预览在切集或超时后清除`() {
        assertFalse(shouldKeepSeekPreview(20_000L, 45_000L, 10_000L, mediaChanged = true, timedOut = false))
        assertFalse(shouldKeepSeekPreview(20_000L, 45_000L, 10_000L, mediaChanged = false, timedOut = true))
    }

    @Test
    fun `多指出现时取消拖动`() {
        assertFalse(isMultiPointerDrag(primaryId = 1L, pressedPointerIds = listOf(1L)))
        assertTrue(isMultiPointerDrag(primaryId = 1L, pressedPointerIds = listOf(1L, 2L)))
    }
}
