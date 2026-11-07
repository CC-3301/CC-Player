package com.cc3301.ccplayer.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 原生播放核心的创建与销毁接力。
 *
 * 释放时先登记一次收尾并发布完成信号，再启动收尾线程；创建方在创建前等待所有已登记的收尾，
 * 等待有上限，超时只回调诊断、不无限阻塞调用线程。
 *
 * 创建与销毁在 [nativeLock] 上互斥：销毁一拿到锁就持有到返回，创建拿不到锁就不创建，
 * 因此不会出现「销毁已开始、创建随后进入」的并发；销毁前还要用 [claimDestroy] 确认期间没有
 * 新核心创建，避免销毁落在后建的核心上。
 *
 * 进程内只应有一个播放核心：正常路径下同一时刻最多一次待收尾登记；登记了多次也会逐一等待。
 */
internal class NativeCoreLifecycle {

    /** 一次释放登记。 */
    class Teardown internal constructor(
        internal val latch: CountDownLatch,
        internal val generation: Long,
    )

    /** 保护待收尾列表与代际的短临界区。 */
    private val stateLock = Any()

    /** 覆盖原生创建与销毁的互斥锁：销毁期间创建方拿不到就放弃本次创建。 */
    private val nativeLock = ReentrantLock()

    private val pending = mutableListOf<CountDownLatch>()

    /** 已开始的创建次数；销毁与创建靠它判断先后。 */
    private var generation = 0L

    /**
     * 创建新的原生核心之前调用：等待已登记的收尾完成，最多 [timeoutMillis]。
     * 等待期间被中断不提前返回，继续等完成信号；到上限仍没有完成信号才回调 [onTimeout]。
     * 之后尝试占用原生锁：销毁已经开始且到点仍未释放时返回 null，调用方放弃本次创建。
     * 返回非 null 表示创建权已拿到，创建结束后必须调用 [finishCreate]。
     */
    fun beginCreate(timeoutMillis: Long, onTimeout: () -> Unit): Long? {
        val waiting = synchronized(stateLock) { pending.toList() }
        if (waiting.isNotEmpty()) {
            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
            var timedOut = false
            for (latch in waiting) {
                if (!awaitUntil(latch, deadline)) timedOut = true
            }
            if (timedOut) onTimeout()
        }
        // 收尾线程可能已经持有原生锁（销毁进行中）：拿不到就放弃创建，绝不与销毁并发。
        if (!nativeLock.tryLock()) return null
        return synchronized(stateLock) {
            generation += 1
            generation
        }
    }

    /** 创建结束：放开原生锁。 */
    fun finishCreate() {
        nativeLock.unlock()
    }

    /** 登记一次收尾：完成信号在收尾线程启动之前就可以被创建方看到。 */
    fun registerTeardown(): Teardown = synchronized(stateLock) {
        val teardown = Teardown(CountDownLatch(1), generation)
        pending += teardown.latch
        teardown
    }

    /** 收尾结束：放行等待中的创建方。重复调用无副作用。 */
    fun finishTeardown(teardown: Teardown) {
        synchronized(stateLock) { pending.remove(teardown.latch) }
        teardown.latch.countDown()
    }

    /**
     * 真正销毁之前确认：登记之后没有新核心创建过。
     * 返回 true 时原生锁由调用方持有，销毁结束后必须调用 [finishDestroy]；false 时锁已放开。
     */
    fun claimDestroy(teardown: Teardown): Boolean {
        // 后台收尾线程上等正在进行的创建结束，时间有界（创建本身不等待）。
        nativeLock.lock()
        val allowed = synchronized(stateLock) {
            if (generation != teardown.generation) {
                false
            } else {
                generation += 1
                true
            }
        }
        if (!allowed) nativeLock.unlock()
        return allowed
    }

    /** 销毁结束：放开原生锁。 */
    fun finishDestroy() {
        nativeLock.unlock()
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
