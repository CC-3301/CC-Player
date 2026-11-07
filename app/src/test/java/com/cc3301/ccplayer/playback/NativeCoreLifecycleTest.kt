package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 原生核心的创建/销毁接力：创建方只等有上限的收尾信号，创建与销毁在原生锁上互斥。 */
class NativeCoreLifecycleTest {

    @Test
    fun `创建等待已经登记的收尾完成`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()
        val created = CountDownLatch(1)
        var createGeneration: Long? = null
        val creator = Thread {
            createGeneration = lifecycle.beginCreate(2_000) { }
            lifecycle.finishCreate()
            created.countDown()
        }
        creator.start()

        // 收尾信号没有发出：创建必须等着，不能直接放行。
        assertFalse(created.await(100, TimeUnit.MILLISECONDS))

        lifecycle.finishTeardown(teardown)
        assertTrue(created.await(2_000, TimeUnit.MILLISECONDS))
        creator.join(2_000)
        assertNotNull(createGeneration)
    }

    @Test
    fun `收尾一直不完成时等待到上限并留诊断`() {
        val lifecycle = NativeCoreLifecycle()
        lifecycle.registerTeardown()
        var timeouts = 0
        val startedNanos = System.nanoTime()
        val generation = lifecycle.beginCreate(50) { timeouts += 1 }
        lifecycle.finishCreate()
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        assertEquals(1, timeouts)
        assertNotNull(generation)
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
            lifecycle.finishCreate()
            created.countDown()
        }.start()

        lifecycle.finishTeardown(first)
        assertFalse(created.await(100, TimeUnit.MILLISECONDS))

        lifecycle.finishTeardown(second)
        assertTrue(created.await(2_000, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `销毁进行中时创建放弃，销毁返回后才能创建`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()
        val destroyStarted = CountDownLatch(1)
        val destroyRelease = CountDownLatch(1)
        val destroyFinished = CountDownLatch(1)
        val destroyer = Thread {
            if (lifecycle.claimDestroy(teardown)) {
                try {
                    destroyStarted.countDown()
                    destroyRelease.await(2, TimeUnit.SECONDS)
                } finally {
                    lifecycle.finishDestroy()
                }
            }
            lifecycle.finishTeardown(teardown)
            destroyFinished.countDown()
        }
        destroyer.start()
        assertTrue(destroyStarted.await(2, TimeUnit.SECONDS))

        // 销毁还没返回：创建等收尾信号到上限后拿不到原生锁，放弃本次创建。
        assertNull(lifecycle.beginCreate(100) { })

        destroyRelease.countDown()
        assertTrue(destroyFinished.await(2, TimeUnit.SECONDS))

        // 销毁返回后才能创建。
        assertNotNull(lifecycle.beginCreate(2_000) { })
        lifecycle.finishCreate()
    }

    @Test
    fun `登记之后创建过新核心就放弃销毁`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()
        // 等待超时后新核心仍会创建：旧收尾不能销毁它。
        lifecycle.beginCreate(50) { }
        lifecycle.finishCreate()

        assertFalse(lifecycle.claimDestroy(teardown))
    }

    @Test
    fun `没有新核心时只允许销毁一次`() {
        val lifecycle = NativeCoreLifecycle()
        val teardown = lifecycle.registerTeardown()

        assertTrue(lifecycle.claimDestroy(teardown))
        lifecycle.finishDestroy()
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
            lifecycle.finishCreate()
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
