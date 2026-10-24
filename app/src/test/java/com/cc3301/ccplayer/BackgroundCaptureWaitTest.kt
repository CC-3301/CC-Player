package com.cc3301.ccplayer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后台抓帧等待必须有上限，超时后调用方仍能继续分离 Surface。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundCaptureWaitTest {

    @Test
    fun `a completed capture is awaited`() = runTest {
        val capture = launch { }

        assertTrue(awaitBackgroundCapture(capture, timeoutMillis = 1_000))
    }

    @Test
    fun `a stuck capture times out`() = runTest {
        val capture = launch { delay(10_000) }

        assertFalse(awaitBackgroundCapture(capture, timeoutMillis = 2_000))
    }

    @Test
    fun `no capture task completes immediately without waiting`() = runTest {
        assertFalse(awaitBackgroundCapture(capture = null, timeoutMillis = 2_000))
    }
}
