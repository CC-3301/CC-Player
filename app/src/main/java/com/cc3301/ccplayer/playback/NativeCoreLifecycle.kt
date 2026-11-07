package com.cc3301.ccplayer.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 原生播放核心的创建与销毁接力。
 *
 * 释放时先登记一次收尾并发布完成信号，再启动收尾线程；创建方在创建前等待所有已登记的收尾，
 * 等待有上限，超时只回调诊断、不无限阻塞调用线程。销毁方在真正销毁前用 [claimDestroy] 确认
 * 期间没有新核心创建，避免销毁落在后建的核心上。
 *
 * 进程内只应有一个播放核心：正常路径下同一时刻最多一次待收尾登记。
 */
internal class NativeCoreLifecycle {

    /** 一次释放登记。 */
    class Teardown internal constructor(
        internal val latch: CountDownLatch,
        internal val generation: Long,
    )

    private val lock = Any()

    private val pending = mutableListOf<CountDownLatch>()

    /** 已开始的创建次数；销毁与创建靠它判断先后。 */
    private var generation = 0L

    /**
     * 创建新的原生核心之前调用：等待已登记的收尾完成，最多 [timeoutMillis]。
     * 超时或线程被中断都只回调 [onTimeout]，不会无限等待；中断标记在返回前恢复。
     * 返回本次创建对应的代际。
     */
    fun beginCreate(timeoutMillis: Long, onTimeout: () -> Unit): Long {
        val waiting = synchronized(lock) { pending.toList() }
        if (waiting.isNotEmpty()) {
            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
            var timedOut = false
            for (latch in waiting) {
                if (!awaitUntil(latch, deadline)) timedOut = true
            }
            if (timedOut) onTimeout()
        }
        return synchronized(lock) {
            generation += 1
            generation
        }
    }

    /** 登记一次收尾：完成信号在收尾线程启动之前就可以被创建方看到。 */
    fun registerTeardown(): Teardown = synchronized(lock) {
        val teardown = Teardown(CountDownLatch(1), generation)
        pending += teardown.latch
        teardown
    }

    /** 收尾结束：放行等待中的创建方。重复调用无副作用。 */
    fun finishTeardown(teardown: Teardown) {
        synchronized(lock) { pending.remove(teardown.latch) }
        teardown.latch.countDown()
    }

    /** 真正销毁之前确认：登记之后没有新核心创建过。确认成功会占住代际，重复确认返回 false。 */
    fun claimDestroy(teardown: Teardown): Boolean = synchronized(lock) {
        if (generation != teardown.generation) return@synchronized false
        generation += 1
        true
    }

    /** 等完成信号；被中断时清掉中断标记继续等，返回前恢复标记。 */
    private fun awaitUntil(latch: CountDownLatch, deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (true) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return latch.count == 0L
                try {
                    return latch.await(remainingNanos, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
