package com.cc3301.ccplayer.playback

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surface 会话：只做 Surface 与宿主生命周期 adapter，挂载与可渲染事实归挂载恢复。 */
class SurfaceSessionTest {

    private val attached = mutableListOf<Surface>()
    private val operations = mutableListOf<String>()
    private val resized = mutableListOf<Pair<Int, Int>>()
    private var detachCalls = 0
    private var surfaceValid = true

    private fun session(): SurfaceSession = SurfaceSession(
        attach = { surface ->
            operations += "attach"
            attached += surface
        },
        detach = {
            operations += "detach"
            detachCalls++
        },
        resize = { width, height ->
            resized += width to height
            operations += "resize"
        },
        isValid = { surfaceValid },
    )

    @Test
    fun `surface creation is reported for mounting`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)

        assertEquals(listOf("attach"), operations)
        assertSame(surface, attached.single())
        assertTrue(session.hasSurface)
    }

    @Test
    fun `surface destruction reports detach immediately`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onSurfaceDestroyed()

        // Surface 真正销毁就报告分离：调用方没有第二处可摘挂的入口。
        assertEquals(listOf("attach", "detach"), operations)
        assertEquals(1, detachCalls)
        assertFalse(session.hasSurface)
    }

    @Test
    fun `invalid stored surface is not reported for mounting`() {
        val session = session()
        val surface = testSurface()
        surfaceValid = false

        session.onSurfaceCreated(surface)

        assertEquals(emptyList<String>(), operations)
        assertEquals(emptyList<Surface>(), attached)
        assertFalse(session.hasSurface)
    }

    @Test
    fun `surface size changes are forwarded`() {
        val session = session()

        session.onSurfaceCreated(testSurface())
        session.onSurfaceChanged(1920, 1080)
        session.onSurfaceChanged(1280, 720)

        // 尺寸只转发：什么时候下发给播放核心由挂载恢复决定。
        assertEquals(listOf(1920 to 1080, 1280 to 720), resized)
    }

    @Test
    fun `service reconnect reports the stored surface again`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onEngineAvailable()

        // 播放服务晚于 Surface 连上：连上后补挂保存的 Surface。
        assertEquals(listOf("attach", "attach"), operations)
        assertSame(surface, attached.last())
        assertTrue(session.hasSurface)
    }

    @Test
    fun `no detach without a surface destruction callback`() {
        val session = session()
        session.onSurfaceCreated(testSurface())
        operations.clear()

        // 窗口不可见与服务重连都不带 Surface 销毁回调：挂载与视频输出保持不动。
        session.onEngineAvailable()
        session.onSurfaceChanged(1920, 1080)

        assertEquals(listOf("attach", "resize"), operations)
        assertEquals(0, detachCalls)
        assertTrue(session.hasSurface)
    }

    @Test
    fun `release reports detach and forgets the surface`() {
        val session = session()
        session.onSurfaceCreated(testSurface())

        session.release()

        assertEquals(listOf("attach", "detach"), operations)
        assertEquals(1, detachCalls)
        assertFalse(session.hasSurface)
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
