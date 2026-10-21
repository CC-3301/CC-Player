package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
