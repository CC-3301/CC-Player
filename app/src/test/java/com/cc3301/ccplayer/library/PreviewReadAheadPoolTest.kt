package com.cc3301.ccplayer.library

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewReadAheadPoolTest {

    @Test
    fun `两条线程按 rank 派发，预取不越过可见项`() {
        val pool = PreviewReadAheadPool(threadCount = 2, threadName = "rank-pool")
        try {
            // 两条线程各占一块并各自停在门闩上：之后提交的任务只能排队，派发顺序完全由 rank 决定。
            val busy = CountDownLatch(2)
            val gateA = CountDownLatch(1)
            val gateB = CountDownLatch(1)
            pool.submit(PREVIEW_PREFETCH_RANK, "busy-a") {
                busy.countDown()
                gateA.await(5, TimeUnit.SECONDS)
            }
            pool.submit(PREVIEW_PREFETCH_RANK, "busy-b") {
                busy.countDown()
                gateB.await(5, TimeUnit.SECONDS)
            }
            assertTrue("两条线程都应在跑", busy.await(5, TimeUnit.SECONDS))

            val visibleStarted = CountDownLatch(1)
            val holdVisible = CountDownLatch(1)
            val prefetchRan = CountDownLatch(1)
            // 预取先提交、rank 更大：rank 生效时，空出的线程先派发可见项。
            pool.submit(PREVIEW_PREFETCH_RANK, "prefetch") { prefetchRan.countDown() }
            pool.submit(1L, "visible") {
                visibleStarted.countDown()
                holdVisible.await(5, TimeUnit.SECONDS)
            }

            // 只放行一条线程：它取到的必须是可见项，预取仍在队列里。
            gateA.countDown()
            assertTrue("空出的线程必须先派发可见项", visibleStarted.await(5, TimeUnit.SECONDS))
            assertEquals("可见项没跑完前，预取不得开始", 1L, prefetchRan.getCount())

            holdVisible.countDown()
            gateB.countDown()
            assertTrue("可见项跑完后预取才执行", prefetchRan.await(5, TimeUnit.SECONDS))
        } finally {
            pool.close()
        }
    }

    @Test
    fun `同一条字节源的两次读不交叉`() {
        val pool = PreviewReadAheadPool(threadCount = 2, threadName = "key-pool")
        try {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            pool.submit(1L, "source") {
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            val secondStarted = CountDownLatch(1)
            pool.submit(1L, "source") { secondStarted.countDown() }
            // 换一条字节源的任务必须马上开跑：它证明另一条线程是空的，第二条读仍被 key 挡住。
            val otherStarted = CountDownLatch(1)
            pool.submit(2L, "other") { otherStarted.countDown() }
            assertTrue("另一条字节源应立即开跑", otherStarted.await(5, TimeUnit.SECONDS))
            assertEquals("同一条字节源的读不得交叉", 1L, secondStarted.getCount())

            releaseFirst.countDown()
            assertTrue("前一块读完后才读后一块", secondStarted.await(5, TimeUnit.SECONDS))
        } finally {
            pool.close()
        }
    }

    @Test
    fun `关闭后线程退出且不再执行新任务`() {
        val pool = PreviewReadAheadPool(threadCount = 2, threadName = "close-pool")
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
