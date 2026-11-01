package com.cc3301.ccplayer.playback

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surface 会话：只由 Surface 回调拆挂，挂载与分离幂等。 */
class SurfaceSessionTest {

    private val attached = mutableListOf<Surface>()
    private val operations = mutableListOf<String>()
    private val resized = mutableListOf<Pair<Int, Int>>()
    private val availability = mutableListOf<Boolean>()
    private var detachCalls = 0
    private var surfaceValid = true
    private var engineReady = true

    private fun session(): SurfaceSession = SurfaceSession(
        attach = { surface ->
            operations += "attach"
            attached += surface
            engineReady
        },
        startOutput = { operations += "output" },
        detachEngine = {
            detachCalls++
            operations += "detach"
        },
        resize = { width, height ->
            resized += width to height
            operations += "resize"
        },
        onAvailabilityChanged = { availability += it },
        isValid = { surfaceValid },
    )

    @Test
    fun `mounting the same surface twice attaches once`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onSurfaceCreated(surface)

        assertEquals(1, attached.size)
        assertSame(surface, attached.single())
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `remount applies the size before starting video output`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onSurfaceChanged(1920, 1080)
        session.onSurfaceDestroyed()
        session.detach()
        operations.clear()

        session.onSurfaceCreated(surface)

        assertEquals(listOf("attach", "resize", "output"), operations)
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `surface destruction keeps the mount until the caller detaches`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onSurfaceDestroyed()

        // 销毁只标记不可用：分离由 SurfaceHolder 回调路径随后调用。
        assertEquals(1, attached.size)
        assertEquals(0, detachCalls)
        assertEquals(SurfaceSession.State.Attached, session.state)
        assertFalse(session.hasSurface)

        session.detach()

        assertEquals(1, detachCalls)
        assertEquals(SurfaceSession.State.Detached, session.state)
    }

    @Test
    fun `a new surface replaces a destroyed mount`() {
        val session = session()
        val oldSurface = testSurface()
        val newSurface = testSurface()

        session.onSurfaceCreated(oldSurface)
        session.onSurfaceDestroyed()
        session.onSurfaceCreated(newSurface)

        // 旧 Surface 已销毁但仍挂在引擎上：先分离，再挂新 Surface。
        assertEquals(listOf("attach", "output", "detach", "attach", "output"), operations)
        assertSame(newSurface, attached.last())
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `a reused surface identity is remounted after destruction`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onSurfaceDestroyed()
        session.onSurfaceCreated(surface)

        // Surface 对象身份可能被复用，销毁过就必须重建挂载。
        assertEquals(2, attached.size)
        assertSame(surface, attached.last())
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `availability follows the ready state`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onSurfaceDestroyed()
        session.detach()
        session.onSurfaceCreated(surface)

        // 销毁与分离都不可渲染，各上报一次 false；恢复挂载后重新可用。
        assertEquals(listOf(true, false, false, true), availability)
    }

    @Test
    fun `invalid stored surface is not attached`() {
        val session = session()
        val surface = testSurface()
        session.onSurfaceCreated(surface)
        session.onSurfaceDestroyed()
        session.detach()
        surfaceValid = false

        session.onSurfaceCreated(surface)

        assertEquals(1, attached.size)
        assertSame(surface, attached.single())
        assertFalse(session.hasSurface)
    }

    @Test
    fun `surface size is applied after mounting and on later changes`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onSurfaceChanged(1920, 1080)
        session.onSurfaceDestroyed()
        session.detach()
        session.onSurfaceCreated(surface)

        assertEquals(listOf(1920 to 1080, 1920 to 1080), resized)
    }

    @Test
    fun `service availability attaches the stored surface`() {
        val session = session()
        val surface = testSurface()
        engineReady = false

        session.onSurfaceCreated(surface)
        assertFalse(session.state == SurfaceSession.State.Ready)

        // 播放服务晚于 Surface 连上：连上后补挂保存的 Surface。
        engineReady = true
        session.onEngineAvailable()

        assertEquals(2, attached.size)
        assertSame(surface, attached.last())
        assertTrue(session.hasSurface)
        assertTrue(session.available)
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `engine reconnect only publishes real state transitions`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        // 已挂载且状态未变：重连不重复发可用回调。
        session.onEngineAvailable()
        assertEquals(listOf(true), availability)

        session.onEngineLost()
        session.onEngineAvailable()

        assertEquals(listOf(true, false, true), availability)
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `service loss makes the next connect remount the surface`() {
        val session = session()
        val surface = testSurface()

        session.onSurfaceCreated(surface)
        session.onEngineLost()

        session.onEngineAvailable()

        assertEquals(2, attached.size)
        assertSame(surface, attached.last())
        assertEquals(SurfaceSession.State.Ready, session.state)
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
