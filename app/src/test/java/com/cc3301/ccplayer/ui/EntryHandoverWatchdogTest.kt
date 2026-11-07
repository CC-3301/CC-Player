package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接手兜底计时：按实际时间累计、暂停不计、重置后从头计时。 */
class EntryHandoverWatchdogTest {

    private var nowMillis = 0L
    private val watchdog = EntryHandoverWatchdog(nowMillis = { nowMillis })

    @Test
    fun `按实际时间差累计到点`() {
        watchdog.resume()

        nowMillis = 1_000
        assertFalse(watchdog.onTick())
        nowMillis = 4_999
        assertFalse(watchdog.onTick())
        nowMillis = 5_000
        assertTrue(watchdog.onTick())
    }

    @Test
    fun `暂停期间不计入等待`() {
        watchdog.resume()
        nowMillis = 2_000
        assertFalse(watchdog.onTick())

        watchdog.pause()
        nowMillis = 60_000
        assertFalse(watchdog.onTick())

        watchdog.resume()
        nowMillis = 62_000
        assertFalse(watchdog.onTick())
        nowMillis = 63_000
        assertTrue(watchdog.onTick())
    }

    @Test
    fun `重置后从头计时`() {
        watchdog.resume()
        nowMillis = 4_000
        assertFalse(watchdog.onTick())
        watchdog.reset()

        watchdog.resume()
        nowMillis = 8_000
        assertFalse(watchdog.onTick())
        nowMillis = 9_000
        assertTrue(watchdog.onTick())
    }
}
