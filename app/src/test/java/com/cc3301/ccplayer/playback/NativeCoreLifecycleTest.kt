package com.cc3301.ccplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 原生核心的创建/销毁接力：创建方只等有上限的收尾信号，等不到就放弃创建。 */
class NativeCoreLifecycleTest {

    @Test
    fun `创建等待已经登记的收尾完成`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()
        val created = CountDownLatch(1)
        var allowed = false
        val creator = Thread {
            allowed = lifecycle.beginCreate(2_000)
            created.countDown()
        }
        creator.start()

        // 收尾信号没有发出：创建必须等着，不能直接放行。
        assertFalse(created.await(100, TimeUnit.MILLISECONDS))

        lifecycle.finishTeardown(teardown)
        assertTrue(created.await(2_000, TimeUnit.MILLISECONDS))
        creator.join(2_000)
        assertTrue(allowed)
    }

    @Test
    fun `收尾一直不完成时等待到上限并放弃创建`() {
        val lifecycle = NativeCoreLifecycle()
        lifecycle.registerTeardown()

        val startedNanos = System.nanoTime()
        val allowed = lifecycle.beginCreate(50)
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

        assertFalse(allowed)
        assertTrue(elapsedMillis >= 40)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
