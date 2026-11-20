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
        schedule = { _, task ->
            timedTasks += task
            true
        },
        mount = { events += "mount" },
        detachOutput = { events += "detach" },
        setProperty = { name, value -> events += "$name=$value" },
        openOutput = { events += "output" },
        onMountGivenUp = { timedOut -> events += if (timedOut) "timeout" else "skipped" },
        isValid = { true },
    )

    private fun runPending() {
        while (tasks.isNotEmpty()) tasks.removeFirst()()
    }

    @Test
    fun `挂载超时后重挂并恢复出画`() {
        val recovery = recovery()
        // 占住视频输出线程：挂载只能排队，随后按超时收尾。
        recovery.postMustRun { events += "busy" }

        recovery.attach(surface)
        timedTasks[0].invoke()

        assertEquals(listOf("timeout"), events)

        runPending()

        // 队列恢复后按最新意图重挂，挂上才开输出。
        assertEquals(listOf("timeout", "busy", "mount", "output"), events)
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

        assertEquals(listOf("mount", "android-surface-size=1920x1080", "output"), events)
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
