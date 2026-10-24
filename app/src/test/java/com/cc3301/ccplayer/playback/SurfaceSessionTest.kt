package com.cc3301.ccplayer.playback

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surface 会话：挂载与分离幂等，回前台复用已保存的 Surface。 */
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
        detachEngine = {
            detachCalls++
            operations += "detach"
        },
        resize = { width, height -> resized += width to height },
        onAvailabilityChanged = { availability += it },
        isValid = { surfaceValid },
    )

    @Test
    fun `mounting the same surface twice attaches once`() {
        val session = session()
        val surface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(surface)
        session.onActivityStarted()
        session.onSurfaceCreated(surface)

        assertEquals(1, attached.size)
        assertSame(surface, attached.single())
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `returning to the foreground reattaches without surfaceCreated`() {
        val session = session()
        val surface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(surface)
        session.onActivityStopped()
        session.detach()

        // 回前台时 Surface 没有被销毁，不再等 surfaceCreated。
        session.onActivityStarted()

        assertEquals(2, attached.size)
        assertSame(surface, attached.last())
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `destroyed surface is not reattached on return`() {
        val session = session()
        val surface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(surface)
        session.onActivityStopped()
        session.detach()
        session.onSurfaceDestroyed()

        session.onActivityStarted()

        assertEquals(1, attached.size)
        assertSame(surface, attached.single())
        assertEquals(SurfaceSession.State.Detached, session.state)
    }

    @Test
    fun `background surface destruction waits for the capture detach`() {
        val session = session()
        val surface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(surface)
        session.onActivityStopped()
        session.onSurfaceDestroyed()

        // 退后台时先让抓帧完成，Surface 销毁不立即分离。
        assertEquals(0, detachCalls)

        session.detach()

        assertEquals(1, detachCalls)
    }

    @Test
    fun `foreground surface destruction detaches immediately and only once`() {
        val session = session()

        session.onActivityStarted()
        session.onSurfaceCreated(testSurface())
        session.onSurfaceDestroyed()
        session.detach()

        assertEquals(1, detachCalls)
        assertEquals(SurfaceSession.State.Detached, session.state)
    }

    @Test
    fun `availability follows the ready state`() {
        val session = session()

        session.onActivityStarted()
        session.onSurfaceCreated(testSurface())
        session.onActivityStopped()
        session.onActivityStarted()
        session.onSurfaceDestroyed()

        assertEquals(listOf(true, false, true, false), availability)
    }

    @Test
    fun `invalid stored surface is not attached`() {
        val session = session()
        val surface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(surface)
        session.onActivityStopped()
        session.detach()
        surfaceValid = false

        session.onActivityStarted()

        assertEquals(1, attached.size)
        assertSame(surface, attached.single())
        assertFalse(session.hasSurface)
    }

    @Test
    fun `surface size is applied after mounting and on later changes`() {
        val session = session()
        val surface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(surface)
        session.onSurfaceChanged(1920, 1080)
        session.onActivityStopped()
        session.detach()
        session.onActivityStarted()

        assertEquals(listOf(1920 to 1080, 1920 to 1080), resized)
    }

    @Test
    fun `service availability attaches the stored surface`() {
        val session = session()
        val surface = testSurface()
        engineReady = false

        session.onActivityStarted()
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

        session.onActivityStarted()
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
    fun `a new surface replaces a destroyed background mount`() {
        val session = session()
        val oldSurface = testSurface()
        val newSurface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(oldSurface)
        session.onActivityStopped()
        session.onSurfaceDestroyed()
        session.onActivityStarted()
        session.onSurfaceCreated(newSurface)

        // 旧 Surface 已销毁但仍挂在引擎上：先分离，再挂新 Surface。
        assertEquals(listOf("attach", "detach", "attach"), operations)
        assertSame(newSurface, attached.last())
        assertEquals(SurfaceSession.State.Ready, session.state)
    }

    @Test
    fun `a reused surface identity is remounted after destruction`() {
        val session = session()
        val surface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(surface)
        session.onActivityStopped()
        session.onSurfaceDestroyed()
        session.onActivityStarted()
        session.onSurfaceCreated(surface)

        // Surface 对象身份可能被复用，销毁过就必须重建挂载。
        assertEquals(listOf("attach", "detach", "attach"), operations)
        assertEquals(2, attached.size)
    }

    @Test
    fun `background capture detach is not skipped by a new surface`() {
        val session = session()
        val oldSurface = testSurface()
        val newSurface = testSurface()

        session.onActivityStarted()
        session.onSurfaceCreated(oldSurface)
        session.onActivityStopped()
        session.onSurfaceDestroyed()
        session.onSurfaceCreated(newSurface)
        // 抓帧结束的分离不能被「已有新 Surface」跳过。
        session.detach()
        session.onActivityStarted()

        assertEquals(listOf("attach", "detach", "attach"), operations)
        assertSame(newSurface, attached.last())
    }

    @Test
    fun `service loss makes the next connect remount the surface`() {
        val session = session()
        val surface = testSurface()

        session.onActivityStarted()
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
