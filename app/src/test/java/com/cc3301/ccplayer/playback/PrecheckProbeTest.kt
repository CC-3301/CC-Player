package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切集预检：每个用例都验结论、句柄与读取线程三面。
 * 先后由 latch 控制，不靠真实毫秒赛跑；用完的执行器在收尾里关掉，不留不结束的线程。
 */
class PrecheckProbeTest {

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "precheck-test").apply { isDaemon = true } }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `a readable target is readable and its handle gets closed`() {
        val handle = FakeHandle()
        val reads = AtomicInteger()

        val outcome = probe(NO_BUDGET_MILLIS).probe({ handle }) {
            reads.incrementAndGet()
            true
        }

        assertEquals(PrecheckOutcome.Readable, outcome)
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
        assertEquals(1, reads.get())
    }

    @Test
    fun `a read that outlives the budget is let through and its handle gets closed`() {
        val probe = probe(BUDGET_MILLIS)
        val readStarted = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val handle = FakeHandle()
        try {
            val outcome = probe.probe({ handle }) {
                readStarted.countDown()
                // 不可取消的阻塞：到点只能放行，读本身不会被谁打断。
                readGate.await()
                true
            }

            assertEquals(PrecheckOutcome.SkippedTimeout, outcome)
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
            // 到点就把句柄取走交给关闭线程，不等读取返回。
            assertTrue(handle.awaitClosed())
            assertEquals(1, handle.closeCount)
        } finally {
            readGate.countDown()
        }

        // 读取晚到也不补关第二次。
        awaitIdle()
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `an open that outlives the budget closes the late handle unread`() {
        val probe = probe(BUDGET_MILLIS)
        val openGate = CountDownLatch(1)
        val handle = FakeHandle()
        val reads = AtomicInteger()
        try {
            val outcome = probe.probe({
                openGate.await()
                handle
            }) {
                reads.incrementAndGet()
                true
            }

            assertEquals(PrecheckOutcome.SkippedTimeout, outcome)
        } finally {
            openGate.countDown()
        }

        // 晚到的句柄一落地就关，且不再继续读。
        assertTrue(handle.awaitClosed())
        awaitIdle()
        assertEquals(1, handle.closeCount)
        assertEquals(0, reads.get())
    }

    @Test
    fun `a stuck read lets the next probe through and the previous one still lands`() {
        val probe = probe(STUCK_BUDGET_MILLIS)
        val readStarted = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val handle = FakeHandle()
        val first = AtomicReference<PrecheckOutcome?>(null)
        val firstThread = Thread {
            first.set(probe.probe({ readStarted.countDown(); readGate.await(); handle }) { true })
        }.apply { isDaemon = true }
        firstThread.start()
        try {
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))

            // 上一路还卡在读取里：这一路跳过预检，立刻放行，不打开、不读、不等待。
            val skipped = probe.probe<FakeHandle>({ error("跳过预检时不应打开目标") }) {
                error("跳过预检时不应读取目标")
            }
            assertEquals(PrecheckOutcome.SkippedBusy, skipped)
        } finally {
            readGate.countDown()
        }

        // 上一路最终返回：结果照给，句柄照样关闭。
        firstThread.join(WATCHDOG_MILLIS)
        assertFalse(firstThread.isAlive)
        assertEquals(PrecheckOutcome.Readable, first.get())
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)

        // 上一路收尾之后，同一模块还能再预检。
        val next = FakeHandle()
        val again = probe.probe({ next }) { true }
        assertEquals(PrecheckOutcome.Readable, again)
        assertTrue(next.awaitClosed())
        assertEquals(1, next.closeCount)
    }

    @Test
    fun `a blocked close changes neither the conclusion nor the next probe`() {
        val probe = probe(BUDGET_MILLIS)
        val closeStarted = CountDownLatch(1)
        val closeGate = CountDownLatch(1)
        val stuck = FakeHandle {
            closeStarted.countDown()
            closeGate.await()
        }
        try {
            val outcome = probe.probe({ stuck }) { true }

            // 读到就先交付结论：关闭还卡着也不改判、不延迟。
            assertEquals(PrecheckOutcome.Readable, outcome)
            assertTrue("结论之后才开始关句柄", closeStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
            assertFalse(stuck.closed)

            // 关闭卡着不影响下一路：照常打开、照常读、照常给结论。
            val next = FakeHandle()
            val again = probe.probe({ next }) { true }
            assertEquals(PrecheckOutcome.Readable, again)
            assertTrue(next.awaitClosed())
            assertEquals(1, next.closeCount)
        } finally {
            closeGate.countDown()
        }

        assertTrue(stuck.awaitClosed())
        assertEquals(1, stuck.closeCount)
    }

    @Test
    fun `a target that cannot be opened is unreadable and nothing is read`() {
        val reads = AtomicInteger()

        val outcome = probe(NO_BUDGET_MILLIS).probe<FakeHandle>({ null }) {
            reads.incrementAndGet()
            true
        }

        assertEquals(PrecheckOutcome.Unreadable, outcome)
        assertEquals(0, reads.get())
    }

    @Test
    fun `an open that throws is let through and nothing is read`() {
        val reads = AtomicInteger()

        val outcome = probe(NO_BUDGET_MILLIS).probe<FakeHandle>({ throw IOException("打不开") }) {
            reads.incrementAndGet()
            true
        }

        assertEquals(PrecheckOutcome.SkippedError, outcome)
        assertEquals(0, reads.get())
    }

    @Test
    fun `a read that reports unreadable is unreadable and its handle gets closed`() {
        val handle = FakeHandle()

        val outcome = probe(NO_BUDGET_MILLIS).probe({ handle }) { false }

        assertEquals(PrecheckOutcome.Unreadable, outcome)
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `a read that throws is let through and its handle gets closed`() {
        val handle = FakeHandle()

        val outcome = probe(NO_BUDGET_MILLIS).probe({ handle }) { throw IOException("读不出来") }

        assertEquals(PrecheckOutcome.SkippedError, outcome)
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `a shut down executor is let through`() {
        val shutDown = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        shutDown.shutdownNow()
        val opened = AtomicInteger()

        val outcome = PrecheckProbe(shutDown).probe({
            opened.incrementAndGet()
            FakeHandle()
        }) { true }

        assertEquals(PrecheckOutcome.SkippedClosed, outcome)
        assertEquals(0, opened.get())
    }

    @Test
    fun `a caller that stops waiting leaves the handle to the read thread`() {
        val probe = probe(BUDGET_MILLIS)
        val readStarted = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val handle = FakeHandle()
        val outcome = AtomicReference<PrecheckOutcome?>(null)
        val caller = Thread {
            outcome.set(probe.probe({ handle }) {
                readStarted.countDown()
                readGate.await()
                true
            })
        }.apply { isDaemon = true }
        caller.start()
        try {
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
            caller.interrupt()
            caller.join(WATCHDOG_MILLIS)

            // 调用方不再等：按放行收尾，句柄在作废时就交给关闭线程。
            assertFalse(caller.isAlive)
            assertEquals(PrecheckOutcome.SkippedError, outcome.get())
            assertTrue(handle.awaitClosed())
            assertEquals(1, handle.closeCount)
        } finally {
            readGate.countDown()
        }

        // 读取晚到也不补关第二次。
        awaitIdle()
        assertEquals(1, handle.closeCount)
    }

    private fun probe(timeoutMillis: Long) = PrecheckProbe(executor, timeoutMillis)

    /** 等读取线程收尾：只用于断言收尾之后的状态，不用于判定结论。 */
    private fun awaitIdle() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WATCHDOG_MILLIS)
        while (executor.activeCount > 0 || !executor.queue.isEmpty()) {
            assertTrue("预检线程没有在预算内收尾", System.nanoTime() < deadline)
            Thread.sleep(1)
        }
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

        /** 等句柄被关；关闭在独立线程上，断言不能只看返回值。 */
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
        /** 到点用例的预算：远小于断言看门狗。 */
        const val BUDGET_MILLIS = 100L

        /** 不参与计时的用例用长预算：到点不会抢在断言之前发生。 */
        const val NO_BUDGET_MILLIS = 10_000L

        /** 卡住的读取用长预算：这条用例验的是跳过，不看到点。 */
        const val STUCK_BUDGET_MILLIS = 10_000L

        /** 整个用例的看门狗：预算失效时用例失败而不是挂住。 */
        const val WATCHDOG_MILLIS = 5_000L
    }
}
