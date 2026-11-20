package com.cc3301.ccplayer.playback

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surface 挂载恢复：可用性只认挂载事实，分离当场作废未执行的开输出。
 * 恢复链走 production 使用的同一组合：挂载恢复 + 视频输出队列。
 */
class SurfaceRemountRecoveryTest {

    private val pending = ArrayDeque<() -> Unit>()
    private val timed = mutableListOf<() -> Unit>()
    private val events = mutableListOf<String>()
    private val availability = mutableListOf<Boolean>()
    private var acceptMount = true

    private val surface = testSurface()

    private fun queue(): VideoOutputQueue = VideoOutputQueue(
        execute = { task -> pending.addLast(task) },
        schedule = { _, task ->
            timed += task
            true
        },
    )

    private fun recovery(queue: VideoOutputQueue): SurfaceMountRecovery = SurfaceMountRecovery(
        queue = queue,
        mount = {
            if (acceptMount) events += "mount"
            acceptMount
        },
        detachOutput = { events += "detach" },
        resize = { width, height -> events += "size=${width}x$height" },
        openOutput = { events += "output" },
        onAvailabilityChanged = { availability += it },
    )

    private fun runPending() {
        while (pending.isNotEmpty()) pending.removeFirst()()
    }

    @Test
    fun `挂载、尺寸与开输出由同一组合按序下发`() {
        val recovery = recovery(queue())

        recovery.setSurfaceSize(1920, 1080)
        recovery.attach(surface)
        // 开输出排在视频输出队列上，尚未执行。
        assertEquals(listOf("mount"), events)

        runPending()

        assertEquals(listOf("mount", "size=1920x1080", "output"), events)
        assertEquals(listOf(true), availability)
        assertTrue(recovery.available)
    }

    @Test
    fun `分离清掉挂载事实并投递分离任务`() {
        val recovery = recovery(queue())
        recovery.attach(surface)
        runPending()
        events.clear()

        recovery.detach()
        assertFalse(recovery.available)
        runPending()

        assertEquals(listOf("detach"), events)
        assertEquals(listOf(true, false), availability)
    }

    @Test
    fun `可用性在挂载、分离、超时与引擎断开后与挂载事实一致`() {
        val queue = queue()
        val recovery = recovery(queue)
        // 占住视频输出线程：这次挂载只能排队。
        queue.postAlways { }
        recovery.attach(surface)

        // 只是请求挂载，还没挂上。
        assertFalse(recovery.available)
        assertEquals(emptyList<Boolean>(), availability)

        // 排队挂载超时：意图回滚，可渲染事实仍不存在。
        timed.removeAt(0).invoke()
        assertFalse(recovery.available)
        assertEquals(emptyList<Boolean>(), availability)

        // 队列恢复后按最新意图重挂一次，此时才算可渲染。
        runPending()
        assertTrue(recovery.available)
        assertEquals(listOf(true), availability)

        recovery.detach()
        assertFalse(recovery.available)
        runPending()

        recovery.attach(surface)
        assertTrue(recovery.available)

        recovery.reset()
        assertFalse(recovery.available)
        assertEquals(listOf(true, false, true, false), availability)
    }

    @Test
    fun `旧 Surface 的分离仍在队列时新 Surface 的排队开输出不再执行`() {
        val recovery = recovery(queue())
        val newSurface = testSurface()
        recovery.attach(surface)
        assertEquals(listOf("mount"), events)

        // 旧 Surface 销毁：分离还在队列里，未执行的开输出当场作废。
        recovery.detach()
        // 新 Surface 请求挂载，排在分离之后。
        recovery.attach(newSurface)
        runPending()

        // 没有对已销毁 Surface 开输出的记录：先分离，再挂新 Surface，最后才开输出。
        assertEquals(listOf("mount", "detach", "mount", "output"), events)
        assertEquals(listOf(true, false, true), availability)
    }

    @Test
    fun `重挂超时后放弃，迟到的任务不再挂载`() {
        val queue = queue()
        val recovery = recovery(queue)
        queue.postAlways { }
        recovery.attach(surface)

        timed.removeAt(0).invoke()
        // 重挂也在 3 秒内没等到视频输出线程：放弃这次恢复。
        timed.removeAt(0).invoke()
        runPending()

        assertEquals(emptyList<String>(), events)
        assertFalse(recovery.available)
        assertEquals(emptyList<Boolean>(), availability)
    }

    @Test
    fun `引擎断开后按现有 Surface 补挂`() {
        val recovery = recovery(queue())
        recovery.attach(surface)
        runPending()
        events.clear()

        recovery.reset()
        recovery.attach(surface)
        runPending()

        assertEquals(listOf("mount", "output"), events)
        assertEquals(listOf(true, false, true), availability)
    }

    @Test
    fun `同一个 Surface 重复请求只挂一次`() {
        val recovery = recovery(queue())
        recovery.attach(surface)
        runPending()
        recovery.attach(surface)
        runPending()

        assertEquals(1, events.count { it == "mount" })
        assertEquals(listOf(true), availability)
    }

    @Test
    fun `挂载被引擎拒绝时不算可渲染`() {
        acceptMount = false
        val recovery = recovery(queue())

        recovery.attach(surface)
        runPending()

        assertEquals(emptyList<String>(), events)
        assertFalse(recovery.available)
        assertEquals(emptyList<Boolean>(), availability)
    }

    private fun testSurface(): Surface = unsafeAllocate(Surface::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> unsafeAllocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, type) as T
    }
}
