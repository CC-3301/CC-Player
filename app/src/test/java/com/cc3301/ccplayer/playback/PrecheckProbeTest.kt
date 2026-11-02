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
 * 覆盖读完、读不动、打不开、打开得晚、调用方取消、关闭阻塞这几种收尾顺序。
 */
class PrecheckProbeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a readable target reports readable and its handle gets closed`() = runBlocking {
        val handle = FakeHandle()
        val reads = AtomicInteger()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = NO_BUDGET_MILLIS).probe({ handle }) {
                reads.incrementAndGet()
                true
            }
        }

        assertTrue(readable)
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
        assertEquals(1, reads.get())
    }

    @Test
    fun `a read that outlives the budget is unreadable and its reader ends`() = runBlocking {
        val readGate = CountDownLatch(1)
        val readExited = CountDownLatch(1)
        // 关闭会放行阻塞读：真实流被关后读会抛错退出，这里走同一个关入口。
        val handle = FakeHandle { readGate.countDown() }

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS).probe({ handle }) {
                try {
                    readGate.await()
                    false
                } finally {
                    readExited.countDown()
                }
            }
        }

        assertFalse(readable)
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
        // 到点收尾后 reader 必须结束，不再占着线程。
        assertTrue("到点后 reader 应已结束", readExited.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a target that cannot be opened is unreadable`() = runBlocking {
        val reads = AtomicInteger()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = NO_BUDGET_MILLIS)
                .probe<FakeHandle>({ null }) {
                    reads.incrementAndGet()
                    true
                }
        }

        assertFalse(readable)
        assertEquals(0, reads.get())
    }

    @Test
    fun `an open that throws is unreadable`() = runBlocking {
        val reads = AtomicInteger()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = NO_BUDGET_MILLIS)
                .probe<FakeHandle>({ throw IOException("打不开") }) {
                    reads.incrementAndGet()
                    true
                }
        }

        assertFalse(readable)
        assertEquals(0, reads.get())
    }

    @Test
    fun `an open that outlives the budget is unreadable and the late handle is closed unread`() = runBlocking {
        val openGate = CountDownLatch(1)
        val handle = FakeHandle()
        val reads = AtomicInteger()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS).probe({
                openGate.await()
                handle
            }) {
                reads.incrementAndGet()
                true
            }
        }

        assertFalse(readable)
        // 预算到点后才放行打开：晚到的句柄一登记就被关掉，且不再继续读。
        openGate.countDown()
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
        assertEquals(0, reads.get())
    }

    @Test
    fun `cancelling the caller ends the reader and still closes the handle`() = runBlocking {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readGate = CountDownLatch(1)
        val readStarted = CountDownLatch(1)
        val readExited = CountDownLatch(1)
        val handle = FakeHandle { readGate.countDown() }
        try {
            val caller = callerScope.launch {
                PrecheckProbe(callerScope, timeoutMillis = BUDGET_MILLIS).probe({ handle }) {
                    readStarted.countDown()
                    try {
                        readGate.await()
                        false
                    } finally {
                        readExited.countDown()
                    }
                }
            }
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))

            // 调用方取消后立刻结束，不回等阻塞读；关闭由独立任务完成。
            withTimeout(WATCHDOG_MILLIS) { caller.cancelAndJoin() }
            assertFalse(caller.isActive)
            assertTrue(handle.awaitClosed())
            assertEquals(1, handle.closeCount)
            assertTrue("取消后 reader 应已结束", readExited.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
        } finally {
            readGate.countDown()
            callerScope.cancel()
        }
    }

    @Test
    fun `a blocked close does not delay the conclusion and the handle still gets closed`() = runBlocking {
        val readGate = CountDownLatch(1)
        val readExited = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeGate = CountDownLatch(1)
        val handle = FakeHandle {
            closeStarted.countDown()
            closeGate.await()
        }
        try {
            // 关闭故意卡住：结论必须在预算到点时自己产出，不能等关闭，更不能等外层 4 秒兜底。
            val readable = withTimeout(2_000) {
                PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS).probe({ handle }) {
                    try {
                        readGate.await()
                        false
                    } finally {
                        readExited.countDown()
                    }
                }
            }

            assertFalse(readable)
            assertTrue("结论产出后关闭应已开始", closeStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
        } finally {
            closeGate.countDown()
            readGate.countDown()
        }
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
        assertTrue(readExited.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a read failure is unreadable and its handle gets closed`() = runBlocking {
        val handle = FakeHandle()

        val readable = withTimeout(WATCHDOG_MILLIS) {
            PrecheckProbe(scope, timeoutMillis = NO_BUDGET_MILLIS).probe({ handle }) {
                throw IOException("读不出来")
            }
        }

        assertFalse(readable)
        assertTrue(handle.awaitClosed())
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
            onClose()
            closed = true
        }

        /** 等句柄被关；关闭发生在独立任务里，断言不能只看返回值。 */
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

        /** 不参与计时的用例用长预算：到点不会抢在断言之前发生。 */
        const val NO_BUDGET_MILLIS = 10_000L

        /** 整个用例的看门狗：预算失效时用例失败而不是挂住。 */
        const val WATCHDOG_MILLIS = 5_000L
    }
}
