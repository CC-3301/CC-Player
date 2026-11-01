package com.cc3301.ccplayer.library

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewReadAheadPoolTest {

    @Test
    fun `唯一线程按 rank 派发，预取不越过可见项`() {
        val pool = PreviewReadAheadPool(threadName = "rank-pool")
        try {
            // 唯一线程先占在一块上停在门闩上：之后提交的任务只能排队，派发顺序完全由 rank 决定。
            val busy = CountDownLatch(1)
            val gate = CountDownLatch(1)
            pool.submit(PREVIEW_PREFETCH_RANK, "busy") {
                busy.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
            assertTrue("唯一的线程应在跑", busy.await(5, TimeUnit.SECONDS))

            val visibleStarted = CountDownLatch(1)
            val holdVisible = CountDownLatch(1)
            val prefetchRan = CountDownLatch(1)
            // 预取先提交、rank 更大：rank 生效时，空出的线程先派发可见项。
            pool.submit(PREVIEW_PREFETCH_RANK, "prefetch") { prefetchRan.countDown() }
            pool.submit(1L, "visible") {
                visibleStarted.countDown()
                holdVisible.await(5, TimeUnit.SECONDS)
            }

            // 只放行那一块：空出的线程取到的必须是可见项，预取仍在队列里。
            gate.countDown()
            assertTrue("空出的线程必须先派发可见项", visibleStarted.await(5, TimeUnit.SECONDS))
            assertEquals("可见项没跑完前，预取不得开始", 1L, prefetchRan.getCount())

            holdVisible.countDown()
            assertTrue("可见项跑完后预取才执行", prefetchRan.await(5, TimeUnit.SECONDS))
        } finally {
            pool.close()
        }
    }

    @Test
    fun `同一条字节源的两次读不交叉`() {
        val pool = PreviewReadAheadPool(threadName = "key-pool")
        try {
            val inFlight = AtomicInteger(0)
            val overlapped = AtomicBoolean(false)
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            pool.submit(1L, "source") {
                if (inFlight.incrementAndGet() > 1) overlapped.set(true)
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
            }
            assertTrue("第一块应开始读", firstStarted.await(5, TimeUnit.SECONDS))

            // 同一字节源的第二块与另一条字节源的任务都在排队：在跑的那块不结束，谁都不许开始。
            val secondStarted = CountDownLatch(1)
            pool.submit(1L, "source") {
                if (inFlight.incrementAndGet() > 1) overlapped.set(true)
                secondStarted.countDown()
                inFlight.decrementAndGet()
            }
            val otherStarted = CountDownLatch(1)
            pool.submit(2L, "other") {
                if (inFlight.incrementAndGet() > 1) overlapped.set(true)
                otherStarted.countDown()
                inFlight.decrementAndGet()
            }
            assertFalse("同一字节源的另一块不得与在跑的这块交叉", secondStarted.await(300, TimeUnit.MILLISECONDS))

            releaseFirst.countDown()
            assertTrue("前一块读完后才读后一块", secondStarted.await(5, TimeUnit.SECONDS))
            assertTrue("另一条字节源的读同样排在后面", otherStarted.await(5, TimeUnit.SECONDS))
            assertFalse("同一时刻不得有两块在读", overlapped.get())
        } finally {
            pool.close()
        }
    }

    @Test
    fun `关闭后线程退出且不再执行新任务`() {
        val pool = PreviewReadAheadPool(threadName = "close-pool")
        val ran = CountDownLatch(2)
        repeat(2) { index ->
            pool.submit(index.toLong(), "key-$index") { ran.countDown() }
        }
        assertTrue(ran.await(5, TimeUnit.SECONDS))

        pool.close()

        assertTrue("关闭后不得留下预读线程：${aliveWorkers("close-pool")}", aliveWorkers("close-pool").isEmpty())
        val afterClose = CountDownLatch(1)
        pool.submit(0L, "late") { afterClose.countDown() }
        assertFalse("关闭后提交的任务不执行", afterClose.await(200, TimeUnit.MILLISECONDS))
    }

    private fun aliveWorkers(prefix: String): List<String> =
        Thread.getAllStackTraces().keys
            .filter { thread -> thread.isAlive && thread.name.startsWith(prefix) }
            .map { thread -> thread.name }
}
