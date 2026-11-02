package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 切集预检的读取预算：打开句柄读 1 个字节，到点按不可读收尾。
 *
 * 一轮预检的句柄、结论、reader 与到点任务都归 [Round] 所有：open 一返回就登记句柄，
 * 读完、到点、取消都走同一个结论入口，结论由一次 CAS 抢占；
 * 结论先交出，关闭随后在独立任务里执行，既不挡结论也不随调用方取消。
 * 同一时刻只允许一路预检实际读取：新一轮开始前先作废上一轮并等它的 reader 退出。
 */
internal class PrecheckProbe(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS,
) {

    /** 到点与关闭的执行者：不随调用方作用域取消，任务都有界。 */
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化预检：同一时刻只跑一轮。 */
    private val gate = Mutex()

    /** 上一轮预检；只在 [gate] 内访问。 */
    private var previous: Round<*>? = null

    /**
     * [open] 打开目标读取句柄（打不开返回 null），[read] 读 1 个字节。
     * 返回是否可读；返回前结论已定，句柄已交给关闭任务。
     */
    suspend fun <T : Closeable> probe(open: suspend () -> T?, read: (T) -> Boolean): Boolean =
        gate.withLock {
            val round = Round<T>(timerScope)
            // 连点时先作废上一轮并等它的 reader 退出，不叠第二条阻塞读。
            previous?.abandon()
            previous = round
            round.reader = scope.launch(Dispatchers.IO) { round.readTarget(open, read) }
            round.deadline = timerScope.launch {
                delay(timeoutMillis)
                round.settle(false)
            }
            try {
                round.outcome.await()
            } finally {
                // 提前返回或调用方取消都在这里收尾；结论已定时 settle 不再改写。
                round.settle(false)
                round.deadline?.cancel()
                round.reader?.cancel()
            }
        }

    /** 一轮预检：句柄、结论、reader 与到点任务的所有者。 */
    private class Round<T : Closeable>(private val timerScope: CoroutineScope) {
        private val handles = HandleSlot<T>()
        private val decided = AtomicBoolean(false)

        val outcome = CompletableDeferred<Boolean>()

        @Volatile
        var reader: Job? = null

        @Volatile
        var deadline: Job? = null

        /**
         * 唯一结论入口：一次 CAS 抢占结论，抢到的路径定结果。
         * 先交结论，再结束 reader、关句柄；关闭不挡结论。
         */
        fun settle(readable: Boolean) {
            if (!decided.compareAndSet(false, true)) return
            outcome.complete(readable)
            reader?.cancel()
            val target = handles.take() ?: return
            // 关闭不随调用方取消；关闭即使阻塞也只占这一个任务。
            timerScope.launch { closeQuietly(target) }
        }

        /** 作废这一轮：先给结论，再等 reader 退出，下一轮才开始。 */
        suspend fun abandon() {
            deadline?.cancel()
            settle(false)
            reader?.join()
        }

        suspend fun readTarget(open: suspend () -> T?, read: (T) -> Boolean) {
            val target = try {
                open()
            } catch (cancellation: CancellationException) {
                settle(false)
                throw cancellation
            } catch (failure: Throwable) {
                null
            }
            if (target == null) {
                settle(false)
                return
            }
            // 预检已收尾时，晚到的句柄在登记处立即关掉，不再继续读。
            if (!handles.register(target)) return
            val readable = try {
                read(target)
            } catch (cancellation: CancellationException) {
                settle(false)
                throw cancellation
            } catch (failure: Throwable) {
                false
            }
            settle(readable)
        }
    }

    /** 句柄的所有权：登记与关闭都走这里，保证最多关闭一次，晚到的句柄不会被漏掉。 */
    private class HandleSlot<T : Closeable> {
        private val handle = AtomicReference<T?>(null)
        private val closed = AtomicBoolean(false)

        /** 登记刚打开的句柄；已收尾时立即关掉并返回 false，调用方不再继续读。 */
        fun register(target: T): Boolean {
            handle.set(target)
            if (!closed.get()) return true
            if (handle.compareAndSet(target, null)) closeQuietly(target)
            return false
        }

        /** 收尾：标记关闭并取走句柄；取到句柄的一方才关闭，保证最多关一次。 */
        fun take(): T? {
            closed.set(true)
            return handle.getAndSet(null)
        }
    }
}

private fun closeQuietly(target: Closeable) {
    runCatching { target.close() }
}
