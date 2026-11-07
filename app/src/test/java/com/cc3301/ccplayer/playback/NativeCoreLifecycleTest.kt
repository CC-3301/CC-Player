package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 原生核心的创建/销毁接力：创建方只等有上限的收尾信号，销毁方只在没有新核心时动手。 */
class NativeCoreLifecycleTest {

    @Test
    fun `创建等待已经登记的收尾完成`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()
        val created = CountDownLatch(1)
        var createGeneration = 0L
        val creator = Thread {
            createGeneration = lifecycle.beginCreate(2_000) { }
            created.countDown()
        }
        creator.start()

        // 收尾信号没有发出：创建必须等着，不能直接放行。
        assertFalse(created.await(100, TimeUnit.MILLISECONDS))

        lifecycle.finishTeardown(teardown)
        assertTrue(created.await(2_000, TimeUnit.MILLISECONDS))
        creator.join(2_000)
        assertTrue(createGeneration > 0)
    }

    @Test
    fun `收尾一直不完成时等待到上限并留诊断`() {
        val lifecycle = NativeCoreLifecycle()
        lifecycle.registerTeardown()
        var timeouts = 0
        val startedNanos = System.nanoTime()
        lifecycle.beginCreate(50) { timeouts += 1 }
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        assertEquals(1, timeouts)
        assertTrue(elapsedMillis >= 40)
    }

    @Test
    fun `多个待收尾都要等到`() {
        val lifecycle = NativeCoreLifecycle()
        val first = lifecycle.registerTeardown()
        val second = lifecycle.registerTeardown()
        val created = CountDownLatch(1)
        Thread {
            lifecycle.beginCreate(2_000) { }
            created.countDown()
        }.start()

        lifecycle.finishTeardown(first)
        assertFalse(created.await(100, TimeUnit.MILLISECONDS))

        lifecycle.finishTeardown(second)
        assertTrue(created.await(2_000, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `登记之后创建过新核心就放弃销毁`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()
        // 等待超时后新核心仍会创建：旧收尾不能销毁它。
        lifecycle.beginCreate(50) { }

        assertFalse(lifecycle.claimDestroy(teardown))
    }

    @Test
    fun `没有新核心时只允许销毁一次`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()

        assertTrue(lifecycle.claimDestroy(teardown))
        assertFalse(lifecycle.claimDestroy(teardown))
    }

    @Test
    fun `中断不打断等待，返回前恢复中断标记`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()
        val created = CountDownLatch(1)
        var interruptedAfter = false
        val creator = Thread {
            Thread.currentThread().interrupt()
            lifecycle.beginCreate(2_000) { }
            interruptedAfter = Thread.currentThread().isInterrupted
            created.countDown()
        }
        creator.start()
        Thread.sleep(50)
        lifecycle.finishTeardown(teardown)

        assertTrue(created.await(2_000, TimeUnit.MILLISECONDS))
        assertTrue(interruptedAfter)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
