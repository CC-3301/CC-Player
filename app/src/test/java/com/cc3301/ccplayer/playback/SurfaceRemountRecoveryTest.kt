package com.cc3301.ccplayer.playback

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surface 挂载恢复：超时 → 按最新意图重挂 → 恢复出画，全程只走 production 使用的同一组合。 */
class SurfaceRemountRecoveryTest {

    private val surface = testSurface()
    private val tasks = ArrayDeque<() -> Unit>()
    private val timedTasks = mutableListOf<() -> Unit>()
    private val events = mutableListOf<String>()

    private fun recovery(): SurfaceMountRecovery = SurfaceMountRecovery(
        execute = { task -> tasks.addLast(task) },
        mount = { events += "mount" },
        detachOutput = { events += "detach" },
        resize = { width, height -> events += "size=${width}x${height}" },
        // 开输出由引擎投递到同一条视频输出线程：这里按同样方式入队，顺序才是 production 的顺序。
        openOutput = { tasks.addLast { events += "output" } },
        schedule = { _, task ->
            timedTasks += task
            true
        },
        isValid = { true },
    )

    private fun runPending() {
        while (tasks.isNotEmpty()) tasks.removeFirst()()
    }

    @Test
    fun `挂载超时后重挂并恢复出画`() {
        val recovery = recovery()
        recovery.attach(surface)
        // 挂载之后队里还有尺寸与分离：新 Surface 的挂载只能排队。
        recovery.setSurfaceSize(1920, 1080)
        recovery.detach()
        recovery.attach(surface)

        timedTasks[0].invoke()
        runPending()

        // 队列恢复后按最新意图重挂：先挂上、再补尺寸、最后开输出。
        assertEquals("mount", events.first())
        assertEquals(listOf("mount", "size=1920x1080", "output"), events.takeLast(3))
        assertTrue(recovery.hasMount())
    }

    @Test
    fun `挂载、尺寸与开输出由同一组合按序下发`() {
        val recovery = recovery()

        recovery.setSurfaceSize(1920, 1080)
        recovery.attach(surface)
        // 尺寸在挂载之后才下发：未挂载时只记下，不碰原生属性。
        assertEquals(listOf("mount"), events)

        runPending()

        assertEquals(listOf("mount", "size=1920x1080", "output"), events)
        assertTrue(recovery.hasMount())
    }

    @Test
    fun `分离清掉挂载事实并投递分离任务`() {
        val recovery = recovery()
        recovery.attach(surface)
        runPending()
        events.clear()

        recovery.detach()
        assertFalse(recovery.hasMount())

        runPending()

        assertEquals(listOf("detach"), events)
    }

    @Test
    fun `引擎断开后按现有 Surface 补挂`() {
        val recovery = recovery()
        recovery.attach(surface)
        runPending()
        events.clear()

        recovery.reset()
        recovery.attach(surface)
        runPending()

        assertEquals(listOf("mount", "output"), events)
        assertTrue(recovery.hasMount())
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
