package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/** 预检结论：可读、不可读或跳过；跳过按放行处理。 */
enum class PrecheckOutcome {
    /** 目标可读：发起切集。 */
    Readable,

    /** 目标不可读：不发起切集，保持当前播放并提示一次。 */
    Unreadable,

    /** 预检线程还在跑上一路：跳过预检直接发起切集，不等待也不提示。 */
    SkippedBusy,

    /** 预检线程已关闭：跳过预检直接发起切集，不等待也不提示。 */
    SkippedClosed,
}

/**
 * 切集预检：打开目标读取句柄读 1 个字节，超过 [timeoutMillis] 按不可读收尾。
 *
 * 预检是优化不是切集门禁：所有读取都在 [executor] 这一条专用线程上跑，同一时刻只有一路；
 * 上一路还没返回时跳过预检、直接放行切集，不等待、不 join、不报失败。
 *
 * 结论只有一个来源：任务交付的结果，或调用方等满预算，二选一。
 * 调用方阻塞等结果，到点就把这一轮作废并立刻判不可读。
 * 关闭可能阻塞，因此不挡结论：读成功先交付结论再关句柄，到点先作废再返回、句柄交给专用线程关。
 * 句柄只有一个关闭入口，[Round.take] 原子取走，谁取到谁关，最多关一次，晚到的句柄落地即关。
 */
internal class PrecheckProbe(
    private val executor: ExecutorService,
    private val timeoutMillis: Long = PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS,
) {

    /** 读取线程是否还在跑一次读；为 true 时本轮跳过预检。 */
    private val readInFlight = AtomicBoolean(false)

    /**
     * [open] 打开目标读取句柄（打不开返回 null），[read] 读 1 个字节。
     * 阻塞到有结论为止：最多等 [timeoutMillis]，到点按不可读返回。
     */
    fun <T : Closeable> probe(open: suspend () -> T?, read: (T) -> Boolean): PrecheckOutcome {
        if (!readInFlight.compareAndSet(false, true)) return PrecheckOutcome.SkippedBusy
        val round = Round<T>(executor, readInFlight)
        val conclusion = CompletableFuture<Boolean>()
        try {
            executor.execute { round.run(open, read, conclusion) }
        } catch (rejected: RejectedExecutionException) {
            // 专用线程已关闭：按跳过处理，放行切集。
            readInFlight.set(false)
            return PrecheckOutcome.SkippedClosed
        }
        return try {
            when (conclusion.get(timeoutMillis, TimeUnit.MILLISECONDS)) {
                true -> PrecheckOutcome.Readable
                false -> PrecheckOutcome.Unreadable
            }
        } catch (timeout: TimeoutException) {
            // 到点：先作废再判不可读；已登记的句柄交给专用线程关，关闭卡住也不挡结论。
            round.abandon()
            PrecheckOutcome.Unreadable
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            round.abandon()
            PrecheckOutcome.Unreadable
        } catch (failure: ExecutionException) {
            round.abandon()
            PrecheckOutcome.Unreadable
        }
    }

    /** 一轮预检：句柄、作废标记与任务体；全部状态都只在这一轮里。 */
    private class Round<T : Closeable>(
        private val executor: ExecutorService,
        private val readInFlight: AtomicBoolean,
    ) {
        private val handle = AtomicReference<T?>(null)

        /** 句柄已被取走：之后落地的句柄一律就地关掉。 */
        private val taken = AtomicBoolean(false)

        /** 任务体：跑在专用线程上。 */
        fun run(open: suspend () -> T?, read: (T) -> Boolean, conclusion: CompletableFuture<Boolean>) {
            try {
                val target = try {
                    runBlocking { open() }
                } catch (failure: Throwable) {
                    null
                }
                // 打不开，或这一轮已经作废（晚到的句柄在 register 里就地关掉）：不读。
                if (target == null || !register(target)) {
                    conclusion.complete(false)
                    return
                }
                val readable = try {
                    read(target)
                } catch (failure: Throwable) {
                    false
                }
                // 先交付结论再关句柄：关闭可能阻塞，既不挡结论，也不能把读成功改成不可读。
                conclusion.complete(readable)
                closeTaken()
            } finally {
                readInFlight.set(false)
            }
        }

        /** 作废这一轮：取走句柄排给专用线程关，调用方不等关闭。 */
        fun abandon() {
            val target = take() ?: return
            try {
                executor.execute { closeQuietly(target) }
            } catch (rejected: RejectedExecutionException) {
                closeQuietly(target)
            }
        }

        /** 登记刚打开的句柄；这一轮已作废时立即关闭并返回 false，调用方不再继续读。 */
        private fun register(target: T): Boolean {
            if (taken.get()) {
                closeQuietly(target)
                return false
            }
            handle.set(target)
            if (!taken.get()) return true
            // 登记期间被取走：关掉刚登记的句柄，且不再读。
            if (handle.compareAndSet(target, null)) closeQuietly(target)
            return false
        }

        /** 唯一关闭入口：原子取走句柄，取到的一方才关闭。 */
        private fun take(): T? {
            taken.set(true)
            return handle.getAndSet(null)
        }

        private fun closeTaken() {
            take()?.let(::closeQuietly)
        }
    }
}

private fun closeQuietly(target: Closeable) {
    runCatching { target.close() }
}
