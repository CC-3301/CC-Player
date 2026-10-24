package com.cc3301.ccplayer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后台抓帧等待的上限与超时后的放弃、分离行为。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundCaptureWaitTest {

    @Test
    fun `a completed capture separates without abandoning the result`() = runTest {
        val capture = launch { }
        var abandoned = 0
        var detached = 0

        awaitCaptureThenDetach(
            capture = capture,
            timeoutMillis = 1_000,
            shouldDetach = { true },
            abandonCapture = { abandoned++ },
            detachSurface = { detached++ },
        )

        assertEquals(0, abandoned)
        assertEquals(1, detached)
    }

    @Test
    fun `a stuck capture abandons the result and still detaches`() = runTest {
        val capture = launch { delay(10_000) }
        var abandoned = 0
        var detached = 0

        awaitCaptureThenDetach(
            capture = capture,
            timeoutMillis = 2_000,
            shouldDetach = { true },
            abandonCapture = { abandoned++ },
            detachSurface = { detached++ },
        )

        assertEquals(1, abandoned)
        assertEquals(1, detached)
    }

    @Test
    fun `no capture task abandons and detaches immediately`() = runTest {
        var abandoned = 0
        var detached = 0

        awaitCaptureThenDetach(
            capture = null,
            timeoutMillis = 2_000,
            shouldDetach = { true },
            abandonCapture = { abandoned++ },
            detachSurface = { detached++ },
        )

        assertEquals(1, abandoned)
        assertEquals(1, detached)
    }

    @Test
    fun `returning to the foreground abandons without detaching`() = runTest {
        val capture = launch { }
        var abandoned = 0
        var detached = 0

        awaitCaptureThenDetach(
            capture = capture,
            timeoutMillis = 2_000,
            shouldDetach = { false },
            abandonCapture = { abandoned++ },
            detachSurface = { detached++ },
        )

        assertEquals(1, abandoned)
        assertEquals(0, detached)
    }

    @Test
    fun `a cancelled wait neither abandons nor detaches`() = runTest {
        val capture = launch { delay(10_000) }
        var abandoned = 0
        var detached = 0
        val waiter = launch {
            awaitCaptureThenDetach(
                capture = capture,
                timeoutMillis = 5_000,
                shouldDetach = { true },
                abandonCapture = { abandoned++ },
                detachSurface = { detached++ },
            )
        }

        advanceTimeBy(1_000)
        runCurrent()
        waiter.cancel()
        waiter.join()

        assertTrue(waiter.isCancelled)
        assertEquals(0, abandoned)
        assertEquals(0, detached)
    }
}
