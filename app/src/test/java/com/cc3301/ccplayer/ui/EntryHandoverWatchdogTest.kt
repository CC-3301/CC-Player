package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.playback.PlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接手兜底：按实际时间累计、暂停不计、到点后走提示与回滚。 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `到点后按会话回滚并使用播放失败文案`() = runTest {
        val scheduler = testScheduler
        val tickerWatchdog = EntryHandoverWatchdog(nowMillis = { scheduler.currentTime })
        var prompted = false
        var exit: FailedEntryExit? = null
        var ticks = 0

        tickerWatchdog.resume()
        val ticker = backgroundScope.launch {
            while (isActive) {
                delay(250)
                ticks += 1
                if (!tickerWatchdog.onTick()) continue
                prompted = true
                exit = failedEntryExit(PlaybackState.Playing, atEpisodeEnd = false)
                break
            }
        }
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(prompted)

        advanceTimeBy(250)
        runCurrent()

        assertTrue(prompted)
        assertEquals(FailedEntryExit.LeavePlayerPage, exit)
        assertEquals(20, ticks)
        assertEquals(R.string.player_playback_failed, handoverTimeoutMessage())
        assertNotEquals(R.string.library_load_failed, handoverTimeoutMessage())
        ticker.cancel()
    }
}
