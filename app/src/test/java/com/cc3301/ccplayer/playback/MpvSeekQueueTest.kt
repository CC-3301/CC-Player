package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎侧 seek 队列：恒为「1 执行 + 1 待执行」，每一跳带永不复用的 seekId 与本代命令序号。
 * 回执只结算已下发且序号相等的那一跳；在途超时按执行完处理并补发。
 * 下发只在命令线程上、且这一跳仍是当前在途时才执行。
 */
class MpvSeekQueueTest {

    /** 交给命令线程的下发任务；测试自己决定何时执行。 */
    private val commands = mutableListOf<Long>()
    private val issued = mutableListOf<Long>()
    private val issuedSerials = mutableListOf<Int>()

    /** 改写后的再次下发从这里取新序号。 */
    private var serialSource = 100
    private val executed = mutableListOf<Pair<Long, Long>>()
    private var timeoutTask: (() -> Unit)? = null

    private val queue = MpvSeekQueue(
        post = { seekId -> commands += seekId },
        onExecuted = { seekId, targetMillis -> executed += seekId to targetMillis },
        armTimeout = { task -> timeoutTask = task },
        cancelTimeout = { timeoutTask = null },
    )

    /** 取走交给命令线程的下发任务；测试自己决定何时执行。 */
    private fun takeCommands(): List<Long> {
        val tasks = commands.toList()
        commands.clear()
        return tasks
    }

    /** 在命令线程上执行排队中的下发任务：只有仍是当前在途的那一跳才真正下发。 */
    private fun runCommandThread() {
        takeCommands().forEach { seekId ->
            queue.issueIfCurrent(seekId) { currentSerial, _, firstIssue ->
                // 首次下发用入队时预留的序号；改写后的再次下发换新序号。
                val serial = if (firstIssue) currentSerial else ++serialSource
                issued += seekId
                issuedSerials += serial
                serial
            }
        }
    }

    @Test
    fun `在途时新目标只覆盖待执行，命令不堆叠`() {
        queue.seek(1, 1, 1_000)
        queue.seek(2, 2, 2_000)
        queue.seek(3, 3, 3_000)
        runCommandThread()

        assertEquals(listOf(1L), issued)
        assertEquals(emptyList<Pair<Long, Long>>(), executed)

        queue.onReceipt(1)
        runCommandThread()

        assertEquals(listOf(1L to 1_000L), executed)
        assertEquals(listOf(1L, 3L), issued)
    }

    @Test
    fun `回执回报它自己的 seekId 并补发待执行那一跳`() {
        queue.seek(7, 1, 1_000)
        queue.seek(8, 2, 2_000)
        runCommandThread()

        assertEquals(7L, queue.onReceipt(1))
        assertEquals(listOf(7L to 1_000L), executed)

        runCommandThread()
        assertEquals(8L, queue.onReceipt(2))
        assertEquals(listOf(7L to 1_000L, 8L to 2_000L), executed)
    }

    @Test
    fun `在途超时同样回报它自己的 seekId 并补发`() {
        queue.seek(5, 1, 5_000)
        queue.seek(6, 2, 6_000)
        runCommandThread()

        timeoutTask?.invoke()

        assertEquals(listOf(5L to 5_000L), executed)
        runCommandThread()
        assertEquals(listOf(5L, 6L), issued)
    }

    @Test
    fun `命令还没下发时 ready 不结算，命令照常下发`() {
        queue.seek(9, 4, 9_000)

        // ready 抢在下发之前到：这一跳还不算「已下发」，不能结算。
        assertNull(queue.onReceipt(4))
        assertEquals(emptyList<Pair<Long, Long>>(), executed)

        // 命令照常交给 mpv，之后按这一跳自己的回执结算。
        runCommandThread()
        assertEquals(listOf(9L), issued)
        assertEquals(9L, queue.onReceipt(4))
        assertEquals(listOf(9L to 9_000L), executed)
    }

    @Test
    fun `超时结算之后迟到的旧回执不结算下一跳`() {
        queue.seek(1, 1, 1_000)
        queue.seek(2, 2, 2_000)
        runCommandThread()
        val staleTimeout = timeoutTask

        staleTimeout?.invoke()
        assertEquals(listOf(1L to 1_000L), executed)
        runCommandThread()
        assertEquals(listOf(1L, 2L), issued)

        // 前一跳的真实回执迟到：它已按超时结算，序号对不上当前在途的下一跳。
        assertNull(queue.onReceipt(1))
        assertEquals(listOf(1L to 1_000L), executed)

        // 下一跳仍按它自己的回执结算。
        assertEquals(2L, queue.onReceipt(2))
        assertEquals(listOf(1L to 1_000L, 2L to 2_000L), executed)
    }

    @Test
    fun `入队后还没来得及下发时重置，旧目标不会打到新文件上`() {
        queue.seek(1, 1, 1_000)
        queue.seek(2, 2, 2_000)
        val staleTimeout = timeoutTask

        queue.reset()
        queue.onReceipt(1)
        staleTimeout?.invoke()
        runCommandThread()

        assertEquals(emptyList<Long>(), issued)
        assertEquals(emptyList<Pair<Long, Long>>(), executed)
    }

    @Test
    fun `重置之后新的一跳只回报自己的身份`() {
        queue.seek(1, 1, 1_000)
        runCommandThread()

        queue.reset()
        queue.seek(2, 2, 2_000)
        runCommandThread()

        assertEquals(listOf(1L, 2L), issued)
        queue.onReceipt(2)
        assertEquals(listOf(2L to 2_000L), executed)
    }

    @Test
    fun `已换下一跳后，旧的下发任务不再执行`() {
        queue.seek(1, 1, 1_000)
        queue.seek(2, 2, 2_000)
        queue.seek(3, 3, 3_000)
        runCommandThread()

        queue.onReceipt(1)

        // 第一跳的旧任务重复执行：它已不是当前在途，不能再下发。
        queue.issueIfCurrent(1) { serial, _, _ -> issued += 1; serial }
        assertEquals(listOf(1L), issued)
    }

    @Test
    fun `旧计时器到点不影响下一跳`() {
        queue.seek(1, 1, 1_000)
        queue.seek(2, 2, 2_000)
        runCommandThread()
        val staleTimeout = timeoutTask

        queue.onReceipt(1)
        staleTimeout?.invoke()

        assertEquals(listOf(1L to 1_000L), executed)
    }

    @Test
    fun `命令抛异常时这一跳被结清，后续 seek 能继续下发`() {
        queue.seek(1, 1, 1_000)
        queue.seek(2, 2, 2_000)
        val failing = takeCommands()

        // mpv 命令抛异常：这一跳不可能再有回执，按执行完结清，异常照常交给调用方。
        try {
            queue.issueIfCurrent(failing.single()) { _, _, _ -> throw IllegalStateException("命令失败") }
        } catch (_: IllegalStateException) {
        }

        assertEquals(listOf(1L to 1_000L), executed)
        // 待执行那一跳已补发，不会再卡在「在途但未下发」。
        val next = takeCommands()
        assertEquals(listOf(2L), next)

        // 后续 seek 照常下发并按自己的回执结算，落定有出口。
        queue.issueIfCurrent(next.single()) { serial, _, _ -> issued += 2L; serial }
        assertEquals(listOf(2L), issued)
        assertEquals(2L, queue.onReceipt(2))
        assertEquals(listOf(1L to 1_000L, 2L to 2_000L), executed)
    }

    @Test
    fun `计时器到点时命令还没下发，重新计时且下发后不再补计时器`() {
        queue.seek(1, 1, 1_000)
        val beforeIssue = timeoutTask

        // 命令线程还没跑到：到点的计时器不结算这一跳，重新计时等它下发。
        beforeIssue?.invoke()
        assertEquals(emptyList<Pair<Long, Long>>(), executed)
        assertTrue(timeoutTask != null && timeoutTask !== beforeIssue)

        runCommandThread()
        assertEquals(listOf(1L), issued)

        // 命令已下发后到点：按执行完结算，结算之后不再补一个计时器。
        timeoutTask?.invoke()
        assertEquals(listOf(1L to 1_000L), executed)
        assertNull(timeoutTask)
    }

    @Test
    fun `没有在途时不回报也不补发`() {
        queue.onReceipt(1)
        queue.onTimeout()

        assertEquals(emptyList<Pair<Long, Long>>(), executed)
        assertEquals(emptyList<Long>(), commands)
    }

    @Test
    fun `预览目标改写仍在执行的那一跳，待执行被丢弃`() {
        queue.seek(1, 1, 1_000, preview = true)
        queue.seek(2, 2, 2_000, preview = true)
        runCommandThread()
        assertEquals(listOf(1L), issued)
        assertEquals(listOf(1), issuedSerials)

        // 预览目标就地改写：预览的待执行被丢弃，按新序号重新下发。
        assertEquals(MpvSeekQueue.Active(1, 1), queue.retarget(3_000))
        runCommandThread()
        assertEquals(listOf(1L, 1L), issued)
        assertEquals(listOf(1, 101), issuedSerials)

        // 旧序号的 ready 对不上这一跳，不结算；新序号的那条才结算改写后的目标。
        assertNull(queue.onReceipt(1))
        assertEquals(emptyList<Pair<Long, Long>>(), executed)
        assertEquals(1L, queue.onReceipt(101))
        assertEquals(listOf(1L to 3_000L), executed)
        assertEquals(emptyList<Long>(), commands)
    }

    @Test
    fun `预览改写只动预览条目，普通跳转照常排队`() {
        queue.seek(1, 1, 1_000)
        queue.seek(2, 2, 2_000)

        // 在途是普通跳转：不改写，也不丢它的待执行。
        assertNull(queue.retarget(3_000))
        runCommandThread()
        assertEquals(listOf(1L), issued)

        queue.onReceipt(1)
        runCommandThread()
        assertEquals(listOf(1L, 2L), issued)
    }

    @Test
    fun `预览改写保留普通跳转的待执行`() {
        queue.seek(1, 1, 1_000, preview = true)
        queue.seek(2, 2, 2_000)
        queue.retarget(3_000)
        runCommandThread()
        // 改写抢在下发前：这次只下发一条命令，用入队时预留的序号。
        assertEquals(listOf(1L), issued)
        assertEquals(listOf(1), issuedSerials)

        // 普通跳转的待执行不随预览改写一起丢：预览这一跳结算后照常补发。
        assertEquals(1L, queue.onReceipt(1))
        runCommandThread()
        assertEquals(listOf(1L, 2L), issued)
        assertEquals(listOf(1, 2), issuedSerials)
    }

    @Test
    fun `预览改写抢在下发前时只下发最后目标`() {
        queue.seek(1, 1, 1_000, preview = true)
        queue.retarget(2_000)

        // 两次投递排在同一跳上：只有最后目标真正下发一次。
        runCommandThread()
        assertEquals(listOf(1L), issued)
        assertEquals(listOf(1), issuedSerials)
    }

    @Test
    fun `没有在途时预览改写返回空`() {
        assertNull(queue.retarget(1_000))

        queue.seek(1, 1, 1_000)
        runCommandThread()
        assertEquals(1L, queue.onReceipt(1))

        // 执行完之后没有在途可改写，调用方按新的一跳入队。
        assertNull(queue.retarget(2_000))
    }
}
