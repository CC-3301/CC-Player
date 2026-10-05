package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationLabelTest {

    @Test
    fun `不足一分钟按秒显示`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(999))
        assertEquals("0:59", formatDuration(59_000))
    }

    @Test
    fun `不足一小时按分秒显示`() {
        assertEquals("3:07", formatDuration(187_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun `满一小时按时分秒显示`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun `负数按零处理`() {
        assertEquals("0:00", formatDuration(-1))
    }
}
