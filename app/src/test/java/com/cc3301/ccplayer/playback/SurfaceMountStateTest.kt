package com.cc3301.ccplayer.playback

import android.view.Surface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 挂载两态：意图与已挂在同一把锁里迁移，分离一起清空。 */
class SurfaceMountStateTest {

    @Test
    fun `挂载回填后分离清空意图与已挂`() {
        val state = SurfaceMountState()
        val surface = testSurface()

        assertTrue(state.require(surface))
        assertFalse(state.require(surface))
        assertTrue(state.isIntended(surface))

        state.onMounted(surface)
        assertTrue(state.isIntended(surface))
        assertTrue(state.hasMount())
        assertTrue(state.detach())
        assertFalse(state.hasMount())

        // 超时只回滚在途意图；重复回滚不再动状态。
        assertTrue(state.require(surface))
        assertTrue(state.onTimedOut(surface))
        assertFalse(state.hasMount())
        assertFalse(state.onTimedOut(surface))
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
