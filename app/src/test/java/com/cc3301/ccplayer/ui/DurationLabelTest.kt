package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationLabelTest {

    @Test
    fun `不足一分钟按秒显示并补足分钟两位`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:00", formatDuration(999))
        assertEquals("00:59", formatDuration(59_000))
    }

    @Test
    fun `不足一小时按分秒显示并补足分钟两位`() {
        assertEquals("03:07", formatDuration(187_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun `满一小时按时分秒显示`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun `负数按零处理`() {
        assertEquals("00:00", formatDuration(-1))
    }

    @Test
    fun `拖动提示的增减量用方括号括起、带正负号且分钟补足两位`() {
        assertEquals("[+00:15]", seekHintDeltaText(15_000L))
        assertEquals("[-00:15]", seekHintDeltaText(-15_000L))
        assertEquals("[+00:00]", seekHintDeltaText(0L))
        assertEquals("[+00:20]", seekHintDeltaText(20_000L))
        assertEquals("[+1:02:03]", seekHintDeltaText(3_723_000L))
    }

    @Test
    fun `进度条时间优先显示拖动预览`() {
        assertEquals(10_000L, seekBarProgressMillis(10_000L, 100_000L, null))
        assertEquals(45_000L, seekBarProgressMillis(10_000L, 100_000L, 45_000L))
    }

    @Test
    fun `进度条时间限制在总时长内`() {
        assertEquals(0L, seekBarProgressMillis(10_000L, 100_000L, -1L))
        assertEquals(100_000L, seekBarProgressMillis(10_000L, 100_000L, 150_000L))
        assertEquals(100_000L, seekBarProgressMillis(150_000L, 100_000L, null))
        assertEquals(0L, seekBarProgressMillis(-1L, 100_000L, null))
    }

    @Test
    fun `未知时长不显示进度`() {
        assertEquals(0L, seekBarProgressMillis(10_000L, 0L, 45_000L))
        assertEquals(0L, seekBarProgressMillis(10_000L, -1L, null))
    }

    @Test
    fun `未知时长显示占位并禁用 seek`() {
        val labels = seekBarLabels(5_000L, 0L, null, "--:--")

        assertEquals("00:05", labels.current)
        assertEquals("--:--", labels.total)
        assertFalse(labels.seekEnabled)
    }

    @Test
    fun `已知时长显示拖动预览与总时长`() {
        val labels = seekBarLabels(10_000L, 100_000L, 45_000L, "--:--")

        assertEquals("00:45", labels.current)
        assertEquals("01:40", labels.total)
        assertTrue(labels.seekEnabled)
    }
}
