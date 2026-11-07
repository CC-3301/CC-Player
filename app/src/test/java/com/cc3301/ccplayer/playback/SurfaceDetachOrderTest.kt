package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** 分离的下发顺序，以及分离与挂载在视频输出投递队列上的先后。 */
class SurfaceDetachOrderTest {

    private val pending = ArrayDeque<() -> Unit>()
    private val queue = VideoOutputQueue(execute = { task -> pending.addLast(task) })

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
        queue.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
        )

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
            val live = VideoOutputQueue(execute = { task -> executor.execute { task() } })
            val calls = mutableListOf<String>()

            // 挂载之前已经释放：当场挂之前再校验一次，不挂。
            live.attach(
                stillCurrent = { false },
                attach = { calls += "attach" },
                onSkipped = { calls += "skipped" },
            )

            assertEquals(listOf("skipped"), calls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `队列跑空时当场挂`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val live = VideoOutputQueue(execute = { task -> executor.execute { task() } })
            val calls = mutableListOf<String>()

            live.attach(
                stillCurrent = { true },
                attach = { calls += "attach" },
                onSkipped = { calls += "skipped" },
            )

            assertEquals(listOf("attach"), calls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `有在途任务时挂载先排队，不等待队列排空`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val live = VideoOutputQueue(execute = { task -> executor.execute { task() } })
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attached = CountDownLatch(1)
            live.postAlways {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val calls = mutableListOf<String>()
            live.attach(
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
            assertEquals(emptyList<String>(), calls)

            release.countDown()
            assertTrue(attached.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("attach"), calls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `尺寸下发与挂载同队列，排在挂载之后`() {
        val pendingTasks = ArrayDeque<() -> Unit>()
        val sizedQueue = VideoOutputQueue(execute = { task -> pendingTasks.addLast(task) })
        val calls = mutableListOf<String>()
        // 占住队列，让挂载只能排队。
        sizedQueue.postAlways { calls += "busy" }
        sizedQueue.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
        )
        sizedQueue.postSurfaceSize(1920, 1080) { name, value -> calls += "$name=$value" }

        while (pendingTasks.isNotEmpty()) pendingTasks.removeFirst()()

        assertEquals(listOf("busy", "attach", "android-surface-size=1920x1080"), calls)
    }

    @Test
    fun `挂载、尺寸与开窗口按投递顺序执行`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val live = VideoOutputQueue(execute = { task -> executor.execute { task() } })
            val busy = CountDownLatch(1)
            val release = CountDownLatch(1)
            // 占住输出线程：挂载只能排队，尺寸与开窗口随后排在同一队列里。
            live.postAlways {
                busy.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(busy.await(2, TimeUnit.SECONDS))

            val calls = mutableListOf<String>()
            live.attach(
                stillCurrent = { true },
                attach = { calls += "attach" },
                onSkipped = { calls += "skipped" },
            )
            live.postSurfaceSize(1920, 1080) { _, _ -> calls += "size" }
            live.postAlways { calls += "output" }
            release.countDown()

            val drained = CountDownLatch(1)
            live.postAlways { drained.countDown() }
            assertTrue(drained.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("attach", "size", "output"), calls)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `排队的挂载超时后按失败收尾，迟到任务不再挂载`() {
        val pendingTasks = ArrayDeque<() -> Unit>()
        var timeoutTask: (() -> Unit)? = null
        val timedQueue = VideoOutputQueue(
            execute = { task -> pendingTasks.addLast(task) },
            schedule = { _, task ->
                timeoutTask = task
                true
            },
        )
        val calls = mutableListOf<String>()
        timedQueue.postAlways { calls += "detach" }
        timedQueue.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
            timeoutMillis = 2_000,
            onTimeout = { calls += "timeout" },
        )

        timeoutTask!!.invoke()
        assertEquals(listOf("timeout"), calls)

        // 输出线程恢复后迟到的任务不再挂载。
        while (pendingTasks.isNotEmpty()) pendingTasks.removeFirst()()
        assertEquals(listOf("timeout", "detach"), calls)
    }

    @Test
    fun `排队的挂载在超时前执行则不再报超时`() {
        val pendingTasks = ArrayDeque<() -> Unit>()
        var timeoutTask: (() -> Unit)? = null
        val timedQueue = VideoOutputQueue(
            execute = { task -> pendingTasks.addLast(task) },
            schedule = { _, task ->
                timeoutTask = task
                true
            },
        )
        val calls = mutableListOf<String>()
        timedQueue.postAlways { calls += "detach" }
        timedQueue.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
            timeoutMillis = 2_000,
            onTimeout = { calls += "timeout" },
        )

        while (pendingTasks.isNotEmpty()) pendingTasks.removeFirst()()
        timeoutTask!!.invoke()

        assertEquals(listOf("detach", "attach"), calls)
    }

    @Test
    fun `超时立即结算排队任务，不等迟到的任务执行`() {
        val tasks = ArrayDeque<() -> Unit>()
        var timeoutTask: (() -> Unit)? = null
        val queue = VideoOutputQueue(
            execute = { task -> tasks.addLast(task) },
            schedule = { _, task ->
                timeoutTask = task
                true
            },
        )
        queue.postAlways { }
        queue.postAlways { }
        queue.attach(
            stillCurrent = { true },
            attach = { },
            onSkipped = { },
            timeoutMillis = 2_000,
            onTimeout = { },
        )
        timeoutTask!!.invoke()

        // 只跑掉两条占位任务；迟到的挂载任务仍留在队列里，但计数已在超时时结算。
        tasks.removeFirst()()
        tasks.removeFirst()()

        val calls = mutableListOf<String>()
        queue.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
        )
        assertEquals(listOf("attach"), calls)
    }

    @Test
    fun `超时与排队挂载并发时只产生一个结果`() {
        val tasks = ArrayDeque<() -> Unit>()
        var timeoutTask: (() -> Unit)? = null
        val queue = VideoOutputQueue(
            execute = { task -> tasks.addLast(task) },
            schedule = { _, task ->
                timeoutTask = task
                true
            },
        )
        queue.postAlways { }
        val calls = Collections.synchronizedList(mutableListOf<String>())
        queue.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
            timeoutMillis = 2_000,
            onTimeout = { calls += "timeout" },
        )
        tasks.removeFirst() // 占位任务
        val queuedAttach = tasks.removeFirst()
        val start = CountDownLatch(1)

        val worker = Thread {
            start.await()
            queuedAttach()
        }
        val timer = Thread {
            start.await()
            timeoutTask!!.invoke()
        }
        worker.start()
        timer.start()
        start.countDown()
        worker.join(2_000)
        timer.join(2_000)

        // 挂载与超时互斥：要么真的挂上，要么按超时收尾。
        assertEquals(1, calls.size)
        assertTrue(calls.single() == "attach" || calls.single() == "timeout")
        // 迟到任务跑完也不会把计数扣成负数。
        queue.postAlways { calls += "after" }
        tasks.removeFirst()()
        assertEquals("after", calls.last())
    }

    @Test
    fun `尺寸投递被拒时留诊断`() {
        val messages = mutableListOf<String>()
        val closed = VideoOutputQueue(
            execute = { throw RejectedExecutionException("已关闭") },
            onDiagnostic = { messages += it },
        )

        closed.postSurfaceSize(1920, 1080) { _, _ -> }

        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("尺寸"))
    }

    @Test
    fun `超时计时没排上时留诊断`() {
        val tasks = ArrayDeque<() -> Unit>()
        val messages = mutableListOf<String>()
        val queue = VideoOutputQueue(
            execute = { task -> tasks.addLast(task) },
            schedule = { _, _ -> false },
            onDiagnostic = { messages += it },
        )
        queue.postAlways { }

        queue.attach(
            stillCurrent = { true },
            attach = { },
            onSkipped = { },
            timeoutMillis = 2_000,
        )

        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("超时"))
    }

    @Test
    fun `内联执行抛异常不会把计数扣成负数`() {
        val queuedTasks = ArrayDeque<() -> Unit>()
        var throwAfterRun = true
        val inline = VideoOutputQueue(execute = { task ->
            if (throwAfterRun) {
                throwAfterRun = false
                task()
                throw IllegalStateException("投递后抛出")
            }
            queuedTasks.addLast(task)
        })
        val calls = mutableListOf<String>()

        // 任务已经执行，投递本身按失败返回。
        assertFalse(inline.postAlways { calls += "first" })
        assertEquals(listOf("first"), calls)

        // 计数已归零：下一次挂载当场执行，不会被误判成有在途任务。
        inline.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
        )
        assertEquals(listOf("first", "attach"), calls)
        assertEquals(0, queuedTasks.size)
    }

    @Test
    fun `在途任务跑完后挂载回到当场执行`() {
        val queuedTasks = ArrayDeque<() -> Unit>()
        val draining = VideoOutputQueue(execute = { task -> queuedTasks.addLast(task) })
        val calls = mutableListOf<String>()
        draining.postAlways { calls += "busy" }
        while (queuedTasks.isNotEmpty()) queuedTasks.removeFirst()()

        draining.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
        )

        assertEquals(listOf("busy", "attach"), calls)
        assertEquals(0, queuedTasks.size)
    }

    @Test
    fun `排队的挂载投递被拒时按跳过收尾`() {
        var submissions = 0
        // 第一次投递占住队列且不执行，第二次投递被拒。
        val rejecting = VideoOutputQueue(execute = { _ ->
            submissions++
            if (submissions > 1) throw RejectedExecutionException("已关闭")
        })
        val calls = mutableListOf<String>()
        rejecting.postAlways { }
        rejecting.attach(
            stillCurrent = { true },
            attach = { calls += "attach" },
            onSkipped = { calls += "skipped" },
        )

        assertEquals(listOf("skipped"), calls)
    }
}
