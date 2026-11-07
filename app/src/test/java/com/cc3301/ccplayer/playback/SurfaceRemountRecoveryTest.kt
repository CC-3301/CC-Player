package com.cc3301.ccplayer.playback

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 挂载超时 → 重挂 → 恢复出画的闭环：重挂只认会话最新意图。 */
class SurfaceRemountRecoveryTest {

    @Test
    fun `挂载超时后重挂并恢复出画`() {
        val state = SurfaceMountState()
        val surface = testSurface()
        val tasks = ArrayDeque<() -> Unit>()
        var timeoutTask: (() -> Unit)? = null
        val queue = VideoOutputQueue(
            execute = { task -> tasks.addLast(task) },
            schedule = { _, task ->
                timeoutTask = task
                true
            },
        )
        val events = mutableListOf<String>()

        assertTrue(state.require(surface))
        queue.postAlways { events += "busy" }
        queue.attach(
            stillCurrent = { state.isIntended(surface) },
            attach = {
                state.onMounted(surface)
                events += "attach"
            },
            onSkipped = { if (state.onSkipped(surface)) events += "skipped" },
            timeoutMillis = 2_000,
            onTimeout = {
                if (state.onTimedOut(surface)) {
                    events += "timeout"
                    queue.postAlways {
                        when (state.remount(surface) { events += "remount" }) {
                            SurfaceMountState.RemountResult.Attached -> {
                                if (state.isMounted(surface)) events += "output"
                            }
                            SurfaceMountState.RemountResult.Superseded -> events += "superseded"
                            SurfaceMountState.RemountResult.Pending -> events += "pending"
                        }
                    }
                }
            },
        )

        timeoutTask!!.invoke()
        while (tasks.isNotEmpty()) tasks.removeFirst()()

        // 超时由计时器触发，先于队列里的占位任务执行；busy 只是占位标记，随后才补跑。
        assertEquals(listOf("timeout", "busy", "remount", "output"), events)
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
