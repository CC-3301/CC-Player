package com.cc3301.ccplayer.playback

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 挂载三态：分离与重挂的交错只能得到「重挂放弃」或「挂上后分离」两种结果。 */
class SurfaceMountStateTest {

    @Test
    fun `分离之后重挂放弃`() {
        val state = SurfaceMountState()
        val surface = testSurface()
        assertTrue(state.require(surface))
        assertTrue(state.onTimedOut(surface))

        assertTrue(state.detach())

        var attached = false
        assertEquals(
            SurfaceMountState.RemountResult.Superseded,
            state.remount(surface) { attached = true },
        )
        assertFalse(attached)
    }

    @Test
    fun `重挂成功之后分离仍要投递分离任务`() {
        val state = SurfaceMountState()
        val surface = testSurface()
        state.require(surface)
        state.onTimedOut(surface)

        var attached = 0
        assertEquals(
            SurfaceMountState.RemountResult.Attached,
            state.remount(surface) { attached += 1 },
        )
        assertEquals(1, attached)
        assertTrue(state.isMounted(surface))

        assertTrue(state.detach())
        assertFalse(state.hasMount())
    }

    @Test
    fun `重挂与分离并发时分离排在挂载之后`() {
        val state = SurfaceMountState()
        val surface = testSurface()
        state.require(surface)
        state.onTimedOut(surface)

        val insideAttach = CountDownLatch(1)
        val releaseAttach = CountDownLatch(1)
        var remountResult: SurfaceMountState.RemountResult? = null
        val mounter = Thread {
            remountResult = state.remount(surface) {
                insideAttach.countDown()
                releaseAttach.await(2, TimeUnit.SECONDS)
            }
        }
        mounter.start()
        assertTrue(insideAttach.await(2, TimeUnit.SECONDS))

        var detached: Boolean? = null
        val detacher = Thread { detached = state.detach() }
        detacher.start()
        // 重挂还在临界区里：分离必须等它挂完。
        detacher.join(100)
        assertTrue(detacher.isAlive)

        releaseAttach.countDown()
        mounter.join(2_000)
        detacher.join(2_000)
        assertEquals(SurfaceMountState.RemountResult.Attached, remountResult)
        assertEquals(true, detached)
        assertFalse(state.hasMount())
    }

    @Test
    fun `换过 Surface 后旧重挂放弃`() {
        val state = SurfaceMountState()
        val oldSurface = testSurface()
        val newSurface = testSurface()
        state.require(oldSurface)
        state.onTimedOut(oldSurface)

        assertTrue(state.require(newSurface))

        var attached = false
        assertEquals(
            SurfaceMountState.RemountResult.Superseded,
            state.remount(oldSurface) { attached = true },
        )
        assertFalse(attached)
    }

    @Test
    fun `挂载执行后回填才算挂上`() {
        val state = SurfaceMountState()
        val surface = testSurface()
        state.require(surface)

        assertFalse(state.isMounted(surface))
        state.onMounted(surface)
        assertTrue(state.isMounted(surface))
    }

    @Test
    fun `已有挂载在途时重挂不重复发起`() {
        val state = SurfaceMountState()
        val surface = testSurface()
        state.require(surface)

        var attached = false
        assertEquals(
            SurfaceMountState.RemountResult.Pending,
            state.remount(surface) { attached = true },
        )
        assertFalse(attached)
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
