package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切集预检的读取预算：3 秒上限由这一层保证。
 * 覆盖「到点返回不可读并关掉句柄」，删掉计时或关句柄都会让这里的用例挂住或失败。
 */
class PrecheckProbeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a readable target closes its handle and reports readable`() = runBlocking {
        val handle = FakeHandle()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope).probe({ handle }) { true }
        }

        assertTrue(readable)
        assertTrue(handle.closed)
    }

    @Test
    fun `a read that outlives the budget is unreadable and its handle gets closed`() = runBlocking {
        val handle = FakeHandle()
        val blocked = CountDownLatch(1)
        try {
            // 阻塞读不响应协程取消：只有「到点关句柄」这条路径能让预算生效。
            val readable = withTimeout(WATCHDOG_MILLIS) {
                PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS)
                    .probe({ handle }) { blocked.await(); true }
            }

            assertFalse(readable)
            assertTrue(handle.closed)
        } finally {
            blocked.countDown()
        }
    }

    @Test
    fun `an open that outlives the budget is unreadable`() = runBlocking {
        val handle = FakeHandle()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS)
                .probe<FakeHandle>({ delay(10_000); handle }) { true }
        }

        assertFalse(readable)
    }

    @Test
    fun `a target that cannot be opened is unreadable`() = runBlocking {
        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope).probe<FakeHandle>({ null }) { true }
        }

        assertFalse(readable)
    }

    @Test
    fun `a read failure is unreadable`() = runBlocking {
        val handle = FakeHandle()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope).probe({ handle }) { throw IOException("读不出来") }
        }

        assertFalse(readable)
        assertTrue(handle.closed)
    }

    private class FakeHandle : Closeable {
        @Volatile
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }

    private companion object {
        /** 用例里的预算：远小于断言看门狗，避免真等 3 秒。 */
        const val BUDGET_MILLIS = 50L

        /** 整个用例的看门狗：预算失效时用例失败而不是挂住。 */
        const val WATCHDOG_MILLIS = 5_000L
    }
}
