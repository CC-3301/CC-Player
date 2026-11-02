package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/** 预检结论：可读、明确读不到，或给不出结论（按放行处理）。 */
enum class PrecheckOutcome {
    /** 目标可读：发起切集。 */
    Readable,

    /** 明确读不到：不发起切集，保持当前播放并提示一次。 */
    Unreadable,

    /** 到点还给不出结论：跳过预检，照常发起切集。 */
    SkippedTimeout,

    /** 给不出结论（读取抛异常、等待被打断）：跳过预检，照常发起切集。 */
    SkippedError,

    /** 读取线程还在跑上一路：跳过预检，照常发起切集，不等待。 */
    SkippedBusy,

    /** 读取线程已关闭：跳过预检，照常发起切集。 */
    SkippedClosed,
}

/**
 * 切集预检：打开目标读取句柄读 1 个字节，[timeoutMillis] 内给不出结论就放行。
 *
 * 预检是优化不是切集门禁：只有明确读不到（打不开、读明确返回不可读）才判不可读；
 * 到点、读取抛异常、上一路未返回、读取线程已关闭都按放行处理，切集照常发起。
 *
 * 读取与关闭分开：读取跑在 [executor] 这一条专用线程上，同一时刻只有一路；
 * 关闭各自起一条短命线程，既不排在读取队列里，也不随 [executor] 关停而丢失。
 * 因此关闭卡住只拖住它自己——不影响结论、不影响下一路预检，也不影响关停后的句柄回收。
 *
 * 结论只有一个来源：任务交付的结果，或调用方等满 [timeoutMillis] 到点，二选一。
 */
internal class PrecheckProbe(
    private val executor: ThreadPoolExecutor,
    private val timeoutMillis: Long = PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS,
) {

    /** 判断空闲与提交任务必须一起做，免得多路同时看见空闲、一起排队。 */
    private val submitLock = Any()

    /**
     * [open] 打开目标读取句柄（打不开返回 null），[read] 读 1 个字节。
     * 最多阻塞调用方 [timeoutMillis]：调用方必须切到 IO 线程再调，协程取消打不断这里的等待。
     * 到点按放行返回，这一轮作废，句柄交给关闭线程。
     */
    fun <T : Closeable> probe(open: suspend () -> T?, read: (T) -> Boolean): PrecheckOutcome {
        val round = Round<T>()
        val conclusion = CompletableFuture<PrecheckOutcome>()
        synchronized(submitLock) {
            // 判据是执行器是否空闲：没有在跑的任务、也没有排队的任务。
            if (executor.activeCount > 0 || !executor.queue.isEmpty()) return PrecheckOutcome.SkippedBusy
            try {
                executor.execute { round.run(open, read, conclusion) }
            } catch (rejected: RejectedExecutionException) {
                return PrecheckOutcome.SkippedClosed
            }
        }
        return try {
            conclusion.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            // 到点：这一轮作废，句柄交给关闭线程，调用方先走。
            round.abandon()
            PrecheckOutcome.SkippedTimeout
        } catch (interrupted: InterruptedException) {
            // 调用方不再等了：同样作废，句柄在读取返回时由读取线程关掉。
            Thread.currentThread().interrupt()
            round.abandon()
            PrecheckOutcome.SkippedError
        }
    }

    /** 一轮预检：句柄与作废标记；全部状态都只在这一轮里。 */
    private class Round<T : Closeable> {

        private val handle = AtomicReference<T?>(null)

        /** 句柄已被取走：之后落地的句柄一律就地关掉。 */
        private val taken = AtomicBoolean(false)

        /** 任务体：跑在读取线程上。 */
        fun run(
            open: suspend () -> T?,
            read: (T) -> Boolean,
            conclusion: CompletableFuture<PrecheckOutcome>,
        ) {
            val target = try {
                runBlocking { open() }
            } catch (failure: Throwable) {
                // 打不开还是判不出来，这里分不清：异常一律按放行。
                conclusion.complete(PrecheckOutcome.SkippedError)
                return
            }
            // 明确打不开：句柄都没有，判不可读。
            if (target == null) {
                conclusion.complete(PrecheckOutcome.Unreadable)
                return
            }
            // 这一轮已经作废：晚到的句柄在 register 里就地关掉，不再读。
            if (!register(target)) return
            val verdict = try {
                if (read(target)) PrecheckOutcome.Readable else PrecheckOutcome.Unreadable
            } catch (failure: Throwable) {
                // 读数抛异常：判不出来，放行。
                PrecheckOutcome.SkippedError
            }
            // 先交付结论再关句柄：关闭另起线程，既不挡结论，也不会把读成功改成不可读。
            conclusion.complete(verdict)
            closeTaken()
        }

        /** 作废这一轮：取走句柄交给关闭线程，调用方不等关闭，也不在调用方线程上关。 */
        fun abandon() {
            take()?.let(::closeAsync)
        }

        /** 登记刚打开的句柄；这一轮已作废时立即关闭并返回 false，调用方不再继续读。 */
        private fun register(target: T): Boolean {
            if (taken.get()) {
                closeAsync(target)
                return false
            }
            handle.set(target)
            if (!taken.get()) return true
            // 登记期间被取走：关掉刚登记的句柄，且不再读。
            if (handle.compareAndSet(target, null)) closeAsync(target)
            return false
        }

        /** 唯一关闭入口：原子取走句柄，取到的一方才关闭。 */
        private fun take(): T? {
            taken.set(true)
            return handle.getAndSet(null)
        }

        private fun closeTaken() {
            take()?.let(::closeAsync)
        }
    }
}

/**
 * 关句柄：另起一条短命守护线程，不与读取共用队列，也不依赖读取执行器是否已关停。
 * 关闭卡住只留一条线程，不挡结论、不挡下一路预检。
 */
private fun closeAsync(target: Closeable) {
    Thread({ closeQuietly(target) }, "switch-precheck-close").apply { isDaemon = true }.start()
}

private fun closeQuietly(target: Closeable) {
    runCatching { target.close() }
}
