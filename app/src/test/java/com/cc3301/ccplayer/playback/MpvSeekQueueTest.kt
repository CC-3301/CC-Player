package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引擎侧 seek 队列：恒为「1 执行 + 1 待执行」，回执与在途超时都按执行完处理并回报目标毫秒。
 */
class MpvSeekQueueTest {

    private val issued = mutableListOf<Long>()
    private val executed = mutableListOf<Long>()
    private var timeoutTask: (() -> Unit)? = null

    private val queue = MpvSeekQueue(
        issue = { targetMillis -> issued += targetMillis },
        onExecuted = { targetMillis -> executed += targetMillis },
        armTimeout = { task -> timeoutTask = task },
        cancelTimeout = { timeoutTask = null },
    )

    @Test
    fun `在途时新目标只覆盖待执行，命令不堆叠`() {
        queue.seek(1_000)
        queue.seek(2_000)
        queue.seek(3_000)

        assertEquals(listOf(1_000L), issued)
        assertEquals(emptyList<Long>(), executed)

        queue.onReceipt()

        assertEquals(listOf(1_000L), executed)
        assertEquals(listOf(1_000L, 3_000L), issued)
    }

    @Test
    fun `回执后补发待执行目标并继续等它自己的回执`() {
        queue.seek(1_000)
        queue.seek(2_000)

        queue.onReceipt()
        assertEquals(listOf(1_000L), executed)

        queue.onReceipt()
        assertEquals(listOf(1_000L, 2_000L), executed)
    }

    @Test
    fun `在途超时按执行完处理并补发待执行目标`() {
        queue.seek(5_000)
        queue.seek(6_000)

        timeoutTask?.invoke()

        assertEquals(listOf(5_000L), executed)
        assertEquals(listOf(5_000L, 6_000L), issued)
    }

    @Test
    fun `重置后在途与待执行都不再回报`() {
        queue.seek(1_000)
        queue.seek(2_000)
        val staleTimeout = timeoutTask

        queue.reset()
        queue.onReceipt()
        staleTimeout?.invoke()

        assertEquals(emptyList<Long>(), executed)
        assertEquals(listOf(1_000L), issued)
    }

    @Test
    fun `旧计时器到点不影响下一跳`() {
        queue.seek(1_000)
        queue.seek(2_000)
        val staleTimeout = timeoutTask

        queue.onReceipt()
        staleTimeout?.invoke()

        assertEquals(listOf(1_000L), executed)
    }

    @Test
    fun `没有在途时不回报也不补发`() {
        queue.onReceipt()
        queue.onTimeout()

        assertEquals(emptyList<Long>(), executed)
        assertEquals(emptyList<Long>(), issued)
    }
}
