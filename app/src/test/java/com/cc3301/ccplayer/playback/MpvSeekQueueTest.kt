package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引擎侧 seek 队列：恒为「1 执行 + 1 待执行」，每一跳带永不复用的 seekId。
 * 回执与在途超时都按执行完处理并回报那一跳自己的身份；下发只在命令线程上、且这一跳仍是当前在途时才执行。
 */
class MpvSeekQueueTest {

    /** 交给命令线程的下发任务；测试自己决定何时执行。 */
    private val commands = mutableListOf<Pair<Long, Long>>()
    private val issued = mutableListOf<Long>()
    private val executed = mutableListOf<Pair<Long, Long>>()
    private var timeoutTask: (() -> Unit)? = null

    private val queue = MpvSeekQueue(
        post = { seekId, targetMillis -> commands += seekId to targetMillis },
        onExecuted = { seekId, targetMillis -> executed += seekId to targetMillis },
        armTimeout = { task -> timeoutTask = task },
        cancelTimeout = { timeoutTask = null },
    )

    /** 在命令线程上执行排队中的下发任务：只有仍是当前在途的那一跳才真正下发。 */
    private fun runCommandThread() {
        val tasks = commands.toList()
        commands.clear()
        tasks.forEach { (seekId, _) ->
            queue.issueIfCurrent(seekId) { issued += seekId }
        }
    }

    @Test
    fun `在途时新目标只覆盖待执行，命令不堆叠`() {
        queue.seek(1, 1_000)
        queue.seek(2, 2_000)
        queue.seek(3, 3_000)
        runCommandThread()

        assertEquals(listOf(1L), issued)
        assertEquals(emptyList<Pair<Long, Long>>(), executed)

        queue.onReceipt()
        runCommandThread()

        assertEquals(listOf(1L to 1_000L), executed)
        assertEquals(listOf(1L, 3L), issued)
    }

    @Test
    fun `回执回报它自己的 seekId 并补发待执行那一跳`() {
        queue.seek(7, 1_000)
        queue.seek(8, 2_000)
        runCommandThread()

        queue.onReceipt()
        assertEquals(listOf(7L to 1_000L), executed)

        queue.onReceipt()
        assertEquals(listOf(7L to 1_000L, 8L to 2_000L), executed)
    }

    @Test
    fun `在途超时同样回报它自己的 seekId 并补发`() {
        queue.seek(5, 5_000)
        queue.seek(6, 6_000)
        runCommandThread()

        timeoutTask?.invoke()

        assertEquals(listOf(5L to 5_000L), executed)
        runCommandThread()
        assertEquals(listOf(5L, 6L), issued)
    }

    @Test
    fun `入队后还没来得及下发时重置，旧目标不会打到新文件上`() {
        queue.seek(1, 1_000)
        queue.seek(2, 2_000)
        val staleTimeout = timeoutTask

        queue.reset()
        queue.onReceipt()
        staleTimeout?.invoke()
        runCommandThread()

        assertEquals(emptyList<Long>(), issued)
        assertEquals(emptyList<Pair<Long, Long>>(), executed)
    }

    @Test
    fun `重置之后新的一跳只回报自己的身份`() {
        queue.seek(1, 1_000)
        runCommandThread()

        queue.reset()
        queue.seek(2, 2_000)
        runCommandThread()

        assertEquals(listOf(1L, 2L), issued)
        queue.onReceipt()
        assertEquals(listOf(2L to 2_000L), executed)
    }

    @Test
    fun `已换下一跳后，旧的下发任务不再执行`() {
        queue.seek(1, 1_000)
        queue.seek(2, 2_000)
        queue.seek(3, 3_000)
        runCommandThread()

        queue.onReceipt()

        // 第一跳的旧任务重复执行：它已不是当前在途，不能再下发。
        queue.issueIfCurrent(1) { issued += 1 }
        assertEquals(listOf(1L), issued)
    }

    @Test
    fun `旧计时器到点不影响下一跳`() {
        queue.seek(1, 1_000)
        queue.seek(2, 2_000)
        runCommandThread()
        val staleTimeout = timeoutTask

        queue.onReceipt()
        staleTimeout?.invoke()

        assertEquals(listOf(1L to 1_000L), executed)
    }

    @Test
    fun `没有在途时不回报也不补发`() {
        queue.onReceipt()
        queue.onTimeout()

        assertEquals(emptyList<Pair<Long, Long>>(), executed)
        assertEquals(emptyList<Pair<Long, Long>>(), commands)
    }
}
