package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切集预检：每个用例都验返回放行与否、句柄与协程三面。
 * 覆盖读完、读不动、打不开、打开得晚、上一路卡住、作废、到点与读成功抢结论、关闭阻塞、调用方取消。
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
        // 读成功路径先关句柄再给结论：结论已落地时句柄必然已关。
        assertTrue(handle.closed)
        assertEquals(1, handle.closeCount)
        assertEquals(1, reads.get())
    }

    @Test
    fun `a read that outlives the budget is unreadable and its reader ends without the close`() = runBlocking {
        val probe = PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS)
        val readStarted = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val readExited = CountDownLatch(1)
        val handle = FakeHandle()
        try {
            val readable = withTimeout(WATCHDOG_MILLIS) {
                probe.probe({ handle }) {
                    readStarted.countDown()
                    try {
                        readGate.await()
                        true
                    } finally {
                        readExited.countDown()
                    }
                }
            }

            assertFalse(readable)
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
            // 到点后读取协程已结束，而读取还卡着：不是靠关闭放行才结束。
            withTimeout(WATCHDOG_MILLIS) { probe.lastReader!!.join() }
            assertEquals("读取仍在进行", 1L, readExited.count)
            assertTrue(handle.awaitClosed())
            assertEquals(1, handle.closeCount)
        } finally {
            readGate.countDown()
        }
        assertTrue(readExited.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `a target that cannot be opened is unreadable and nothing is read`() = runBlocking {
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
    fun `an open that throws is unreadable and nothing is read`() = runBlocking {
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
    fun `an open that outlives the budget closes the late handle unread`() = runBlocking {
        val probe = PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS)
        val openGate = CountDownLatch(1)
        val handle = FakeHandle()
        val reads = AtomicInteger()
        val readable = withTimeout(WATCHDOG_MILLIS) {
            probe.probe({
                openGate.await()
                handle
            }) {
                reads.incrementAndGet()
                true
            }
        }

        assertFalse(readable)
        // 等到点任务收完（句柄已标记关闭）再放行打开：晚到的句柄一登记就被关掉，且不再继续读。
        withTimeout(WATCHDOG_MILLIS) { probe.lastDeadline!!.join() }
        openGate.countDown()

        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
        assertEquals(0, reads.get())
    }

    @Test
    fun `a stuck read is skipped and the next probe proceeds without waiting`() = runBlocking {
        val probe = PrecheckProbe(scope, timeoutMillis = NO_BUDGET_MILLIS)
        val readStarted = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val handle = FakeHandle()
        var firstResult: Boolean? = null
        val firstProbe = launch {
            firstResult = probe.probe({
                readStarted.countDown()
                readGate.await()
                handle
            }) { true }
        }
        try {
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))

            // 上一路还卡在读取里：这一路跳过预检，立刻放行，不打开、不读、不等待。
            val proceed = withTimeout(WATCHDOG_MILLIS) {
                probe.probe<FakeHandle>({ error("跳过预检时不应打开目标") }) {
                    error("跳过预检时不应读取目标")
                }
            }
            assertTrue(proceed)

            // 上一路按代号作废：等待方按取消收尾，结果不交给任何人。
            withTimeout(WATCHDOG_MILLIS) { firstProbe.join() }
            assertTrue(firstProbe.isCancelled)
            assertNull(firstResult)
        } finally {
            readGate.countDown()
        }

        // 上一路最终返回：句柄照样关闭，结果丢弃。
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `a voided round drops its result and still closes the handle`() = runBlocking {
        val probe = PrecheckProbe(scope, timeoutMillis = NO_BUDGET_MILLIS)
        val readStarted = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val handle = FakeHandle()
        var result: Boolean? = null
        val firstProbe = launch {
            result = probe.probe({
                handle
            }) {
                readStarted.countDown()
                readGate.await()
                true
            }
        }
        try {
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(
                probe.probe<FakeHandle>({ error("跳过预检时不应打开目标") }) {
                    error("跳过预检时不应读取目标")
                },
            )
            withTimeout(WATCHDOG_MILLIS) { firstProbe.join() }
            assertNull(result)
            // 作废时句柄交给关闭任务，不等阻塞读返回。
            assertTrue(handle.awaitClosed())
        } finally {
            readGate.countDown()
        }
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `a successful read is not turned unreadable by the deadline while the handle closes`() = runBlocking {
        val probe = PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS)
        val closeStarted = CountDownLatch(1)
        val closeGate = CountDownLatch(1)
        val handle = FakeHandle {
            closeStarted.countDown()
            closeGate.await()
        }
        val result = CompletableDeferred<Boolean>()
        try {
            launch { result.complete(probe.probe({ handle }) { true }) }

            // 读取已成功、结论还没落地：此时关闭还卡着。
            assertTrue(closeStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))
            withTimeout(WATCHDOG_MILLIS) { probe.lastDeadline!!.join() }

            // 到点不得替已经读成功的一路下结论。
            assertFalse("到点不得把读成功改成不可读", result.isCompleted)
        } finally {
            closeGate.countDown()
        }

        assertTrue(withTimeout(WATCHDOG_MILLIS) { result.await() })
        assertTrue(handle.awaitClosed())
        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `a blocked close does not delay the unreadable conclusion`() = runBlocking {
        val probe = PrecheckProbe(scope, timeoutMillis = BUDGET_MILLIS)
        val readGate = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeGate = CountDownLatch(1)
        val handle = FakeHandle {
            closeStarted.countDown()
            closeGate.await()
        }
        try {
            // 关闭故意卡住：结论必须在预算到点时自己产出，不等关闭，更不能等外层 4 秒兜底。
            val readable = withTimeout(WATCHDOG_MILLIS) {
                probe.probe({ handle }) {
                    readGate.await()
                    true
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

    @Test
    fun `cancelling the caller closes the handle and ends the reader`() = runBlocking {
        val probe = PrecheckProbe(scope, timeoutMillis = NO_BUDGET_MILLIS)
        val readStarted = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val handle = FakeHandle()
        val caller = launch {
            probe.probe({
                handle
            }) {
                readStarted.countDown()
                readGate.await()
                true
            }
        }
        try {
            assertTrue(readStarted.await(WATCHDOG_MILLIS, TimeUnit.MILLISECONDS))

            // 调用方取消后立刻结束，不回等阻塞读；关闭由独立任务完成。
            withTimeout(WATCHDOG_MILLIS) { caller.cancelAndJoin() }
            assertFalse(caller.isActive)
            assertTrue(handle.awaitClosed())
            assertEquals(1, handle.closeCount)
            withTimeout(WATCHDOG_MILLIS) { probe.lastReader!!.join() }
        } finally {
            readGate.countDown()
        }
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
