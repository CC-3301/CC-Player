package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切集预检的读取预算：每个用例都验返回值与句柄，不只验其中一面。
 * 覆盖读完、读不动、打开得晚、调用方取消、读成功但还没上报这几种到点顺序。
 */
class PrecheckProbeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a readable target closes its handle and reports readable`() = runBlocking {
        val handle = FakeHandle()
        val reads = AtomicInteger()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope).probe({ handle }) {
                reads.incrementAndGet()
                true
            }
        }

        assertTrue(readable)
        assertTrue(handle.closed)
        assertEquals(1, handle.closeCount)
        assertEquals(1, reads.get())
    }

    @Test
    fun `a read that outlives the budget is unreadable and its handle gets closed`() = runBlocking {
        val handle = FakeHandle()
        val blocked = CountDownLatch(1)
        val readDone = CountDownLatch(1)
        try {
            // 阻塞读不响应协程取消：只有「到点关句柄」这条路径能让预算生效。
            val readable = withTimeout(WATCHDOG_MILLIS) {
                PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS)
                    .probe({ handle }) {
                        blocked.await()
                        readDone.countDown()
                        true
                    }
            }

            assertFalse(readable)
            assertTrue(handle.closed)
            assertEquals(1, handle.closeCount)
        } finally {
            blocked.countDown()
        }
        // 读最终收尾，不留僵尸协程。
        assertTrue(readDone.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `an open that outlives the budget is unreadable and the late handle is closed unread`() = runBlocking {
        val handle = FakeHandle()
        val reads = AtomicInteger()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS)
                .probe<FakeHandle>({ delay(OPEN_DELAY_MILLIS); handle }) {
                    reads.incrementAndGet()
                    true
                }
        }

        assertFalse(readable)
        // 晚到的句柄一登记就被关掉，且不再继续读。
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
        assertEquals(0, reads.get())
    }

    @Test
    fun `cancelling the caller returns at once and the handle is still closed`() = runBlocking {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handle = FakeHandle()
        val readStarted = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val readDone = CountDownLatch(1)
        try {
            val caller = callerScope.launch {
                PrecheckProbe(callerScope, timeoutMillis = BUDGET_MILLIS).probe({ handle }) {
                    readStarted.countDown()
                    readGate.await()
                    readDone.countDown()
                    true
                }
            }
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))

            // 调用方取消后立刻结束，不等阻塞中的读；到点关句柄是独立任务，不受这次取消影响。
            withTimeout(WATCHDOG_MILLIS) { caller.cancelAndJoin() }
            assertFalse(caller.isActive)
            callerScope.cancel()
            assertTrue("句柄应由独立到点任务关闭", handle.awaitClosed())
            assertEquals(1, handle.closeCount)
        } finally {
            readGate.countDown()
            callerScope.cancel()
        }
        assertTrue(readDone.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a read that succeeded before reporting is not reported unreadable at the deadline`() = runBlocking {
        val closeGate = CountDownLatch(1)
        // 关闭动作故意卡住：读已经成功，结果还没上报时到点。
        val handle = FakeHandle { closeGate.await() }

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS).probe({ handle }) { true }
        }

        try {
            assertTrue("读已成功，到点不能判成不可读", readable)
        } finally {
            closeGate.countDown()
        }
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `a read failure is unreadable and its handle gets closed`() = runBlocking {
        val handle = FakeHandle()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope).probe({ handle }) { throw IOException("读不出来") }
        }

        assertFalse(readable)
        assertTrue(handle.closed)
        assertEquals(1, handle.closeCount)
    }

    private class FakeHandle(private val onClose: () -> Unit = {}) : Closeable {
        private val closeCalls = AtomicInteger()

        @Volatile
        var closed = false
            private set

        val closeCount: Int get() = closeCalls.get()

        override fun close() {
            closeCalls.incrementAndGet()
            closed = true
            onClose()
        }

        /** 等句柄被关；用于「关闭发生在调用方返回之后」的路径。 */
        fun awaitClosed(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WATCHDOG_MILLIS)
            while (!closed) {
                if (System.nanoTime() >= deadline) return false
                Thread.sleep(1)
            }
            return true
        }
    }

    private companion object {
        /** 用例里的预算：远小于断言看门狗，避免真等 3 秒。 */
        const val BUDGET_MILLIS = 100L

        /** 打开慢于预算是 200ms，保证句柄在预算之后才到达。 */
        const val OPEN_DELAY_MILLIS = 200L

        /** 整个用例的看门狗：预算失效时用例失败而不是挂住。 */
        const val WATCHDOG_MILLIS = 5_000L
    }
}
