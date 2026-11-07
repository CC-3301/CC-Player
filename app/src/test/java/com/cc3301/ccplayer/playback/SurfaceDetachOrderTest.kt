package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 分离的下发顺序，以及分离与挂载在视频输出投递队列上的先后。 */
class SurfaceDetachOrderTest {

    private val pending = ArrayDeque<() -> Unit>()
    private val queue = VideoOutputQueue { task -> pending.addLast(task) }

    private fun runPending() {
        while (pending.isNotEmpty()) pending.removeFirst()()
    }

    @Test
    fun `分离先关输出再撤窗口，最后摘挂载`() {
        val calls = mutableListOf<String>()

        detachVideoOutput(
            setProperty = { name, value -> calls += "property:$name=$value" },
            setOption = { name, value -> calls += "option:$name=$value" },
            detachSurface = { calls += "detach" },
        )

        assertEquals(listOf("property:vo=null", "option:force-window=no", "detach"), calls)
    }

    @Test
    fun `作废开输出不会作废已投递的分离`() {
        val calls = mutableListOf<String>()
        queue.postAlways { calls += "detach" }

        // 释放路径就是先作废再排空：分离不能跟着一起被作废，否则销毁前根本没分离过。
        queue.invalidate()
        runPending()

        assertEquals(listOf("detach"), calls)
    }

    @Test
    fun `作废之后到点的开输出不再执行`() {
        val calls = mutableListOf<String>()
        queue.postOutput { calls += "open" }

        queue.invalidate()
        runPending()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `有在途任务时挂载排到分离之后，不放行当场挂`() {
        val calls = mutableListOf<String>()
        queue.postAlways { calls += "detach" }

        // 这条线程没有真执行器：分离一直算在途，挂载只能排在它后面。
        val attachedNow = queue.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
        )

        assertFalse(attachedNow)
        assertEquals(emptyList<String>(), calls)

        runPending()
        assertEquals(listOf("detach", "attach"), calls)
    }

    @Test
    fun `排队的挂载在校验不过时跳过`() {
        val calls = mutableListOf<String>()
        queue.postAlways { calls += "detach" }

        queue.attach(
            stillCurrent = { false },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
        )
        runPending()

        assertEquals(listOf("detach", "skipped"), calls)
    }

    @Test
    fun `队列跑空但校验不过时跳过当场挂`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val live = VideoOutputQueue { task -> executor.execute { task() } }
            val calls = mutableListOf<String>()

            // 挂载之前已经释放：当场挂之前再校验一次，不挂。
            val attachedNow = live.attach(
                stillCurrent = { false },
                attach = { calls += "attach" },
                onSkipped = { calls += "skipped" },
            )

            assertFalse(attachedNow)
            assertEquals(listOf("skipped"), calls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `队列跑空时当场挂`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val live = VideoOutputQueue { task -> executor.execute { task() } }
            val calls = mutableListOf<String>()

            val attachedNow = live.attach(
                stillCurrent = { true },
                attach = { calls += "attach" },
                onSkipped = { calls += "skipped" },
            )

            assertTrue(attachedNow)
            assertEquals(listOf("attach"), calls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `有在途任务时挂载立即返回，不等待队列排空`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val live = VideoOutputQueue { task -> executor.execute { task() } }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attached = CountDownLatch(1)
            live.postAlways {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val calls = mutableListOf<String>()
            val attachedNow = live.attach(
                stillCurrent = { true },
                attach = {
                    calls += "attach"
                    attached.countDown()
                },
                onSkipped = {
                    calls += "skipped"
                    attached.countDown()
                },
            )

            // 视频输出线程仍被占着：调用线程拿不到当场挂的机会，先把挂载排队并立即返回。
            assertFalse(attachedNow)
            assertEquals(emptyList<String>(), calls)

            release.countDown()
            assertTrue(attached.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("attach"), calls)
        } finally {
            executor.shutdownNow()
        }
    }
}
