package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** 视频输出队列的先后：分离一定执行到，挂载一定排在分离之后。 */
class SurfaceDetachOrderTest {

    private val pending = ArrayDeque<() -> Unit>()
    private val queue = VideoOutputQueue { task -> pending.addLast(task) }

    private fun runPending() {
        while (pending.isNotEmpty()) pending.removeFirst()()
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
    fun `挂载排在分离之后`() {
        val calls = mutableListOf<String>()
        // 分离还在排队时，挂载只能排在它后面：先摘后挂，不能抢跑。
        queue.postAlways { calls += "detach" }
        queue.postAlways { calls += "attach" }
        queue.postOutput { calls += "open" }

        runPending()

        assertEquals(listOf("detach", "attach", "open"), calls)
    }
}
