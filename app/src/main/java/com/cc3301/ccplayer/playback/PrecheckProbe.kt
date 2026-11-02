package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 切集预检：打开目标读取句柄读 1 个字节，[timeoutMillis] 到点按不可读收尾。
 *
 * 预检是优化不是切集门禁：读取线程固定一条，同一时刻只有一路在跑；
 * 上一路还没退出时跳过预检、直接放行，不等待、不 join、不报失败。
 * 上一路按轮次作废：它最终返回时句柄照样关闭，结果丢弃。
 *
 * 一轮的句柄、结论、读取协程与到点任务都归 [Round] 所有。结论只由一次 CAS 决定：
 * 读者与到点任务抢同一个结论所有权，先抢到的定结果。
 * 读成功路径先关句柄再给结论（读已结束，关闭不拖读取）；
 * 到点/取消路径先给结论再异步关句柄（关闭可能阻塞，不能拖结论）。
 */
internal class PrecheckProbe(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS,
) {

    /** 读取线程固定一条：阻塞读最多占住这一个线程，不随点击次数堆积。 */
    private val readDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "switch-precheck").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** 读取线程是否还在跑一次读；为 true 时本轮跳过预检。 */
    private val readInFlight = AtomicBoolean(false)

    /** 最近一轮预检；新一轮从它按代号作废。 */
    @Volatile
    private var latest: Round<*>? = null

    /** 最近一轮的读取协程；到点后它必须已结束，不等阻塞读返回。 */
    internal val lastReader: Job? get() = latest?.reader

    /** 最近一轮的到点任务；到点确已发生的观测点。 */
    internal val lastDeadline: Job? get() = latest?.deadline

    /**
     * [open] 打开目标读取句柄（打不开返回 null），[read] 读 1 个字节。
     * 返回是否放行切集：可读为 true；读取线程正忙、跳过预检时同样为 true。
     */
    suspend fun <T : Closeable> probe(open: suspend () -> T?, read: (T) -> Boolean): Boolean {
        if (!readInFlight.compareAndSet(false, true)) {
            // 线程正忙：跳过预检直接放行，不等待上一路退出，也不报失败。
            latest?.abandon()
            return true
        }
        val round = Round<T>(scope)
        // 先挂好读取协程与到点任务，再把这一轮交给他人作废：作废时两者都可取消。
        round.reader = scope.launch(Dispatchers.IO) { round.publish() }
        round.deadline = scope.launch {
            delay(timeoutMillis)
            round.expire()
        }
        latest = round
        scope.launch(readDispatcher) {
            try {
                round.read(open, read)
            } finally {
                readInFlight.set(false)
            }
        }
        return try {
            round.outcome.await()
        } catch (cancellation: CancellationException) {
            // 调用方取消或新一轮作废：结果不再要，句柄交给关闭任务。
            round.abandon()
            throw cancellation
        }
    }

    /** 一轮预检：句柄、结论、读取协程与到点任务的所有者。 */
    private class Round<T : Closeable>(private val scope: CoroutineScope) {
        private val handles = HandleSlot<T>()

        /** 结论所有权：null 未定；读者与到点任务谁先 CAS 到谁定。 */
        private val decision = AtomicReference<Boolean?>(null)

        val outcome = CompletableDeferred<Boolean>()
        private val readResult = CompletableDeferred<Boolean>()

        @Volatile
        var reader: Job? = null

        @Volatile
        var deadline: Job? = null

        /**
         * 读取线程上的阻塞段：打开、登记、读 1 个字节，读返回后立刻抢结论。
         * 读成功在返回的那一刻就抢，到点才不能把已经读成功的结论改成不可读。
         */
        suspend fun read(open: suspend () -> T?, read: (T) -> Boolean) {
            val target = try {
                open()
            } catch (failure: Throwable) {
                null
            }
            if (target == null) {
                decide(false)
                readResult.complete(false)
                return
            }
            // 已作废或已到点的轮次：晚到的句柄在登记处立即关掉，不再读。
            if (!handles.register(target)) {
                readResult.complete(false)
                return
            }
            val readable = try {
                read(target)
            } catch (failure: Throwable) {
                false
            }
            decide(readable)
            readResult.complete(readable)
        }

        /** 抢结论所有权：只有第一个 CAS 到的路径能定结果。 */
        private fun decide(readable: Boolean): Boolean = decision.compareAndSet(null, readable)

        /** 读者读完：读成功先关句柄再给结论；不可读先给结论再异步关句柄。 */
        fun publish() {
            when (decision.get()) {
                // 读已结束，关闭不会拖住读取；先关再给结论，结论落地时句柄已关。
                // 到点任务留到结论之后才收：关闭卡住时它照常到点，但抢不走已定下的结论。
                true -> {
                    closeNow()
                    outcome.complete(true)
                    deadline?.cancel()
                }
                // 不可读不先关句柄：关闭可能阻塞，不能拖结论。
                false -> {
                    outcome.complete(false)
                    closeLater()
                    deadline?.cancel()
                }
                null -> Unit
            }
        }

        /** 到点：先给结论再异步关句柄；读取协程当场结束，不等阻塞读返回，也不靠关闭放行。 */
        fun expire() {
            if (!decide(false)) return
            outcome.complete(false)
            reader?.cancel()
            closeLater()
        }

        /** 作废轮次：结果丢弃，等待方按取消收尾，句柄立刻交给关闭任务。 */
        fun abandon() {
            deadline?.cancel()
            reader?.cancel()
            outcome.cancel()
            closeLater()
        }

        private fun closeNow() {
            handles.take()?.let(::closeQuietly)
        }

        private fun closeLater() {
            val target = handles.take() ?: return
            // 关闭不随结论或调用方取消；即使阻塞也只占一个任务。
            scope.launch(Dispatchers.IO) { closeQuietly(target) }
        }
    }

    /** 句柄的唯一所有者：登记与关闭都走这里，最多关一次，晚到的句柄一落地就关。 */
    private class HandleSlot<T : Closeable> {
        private val handle = AtomicReference<T?>(null)
        private val closed = AtomicBoolean(false)

        /** 登记刚打开的句柄；已收尾时立即关掉并返回 false，调用方不再继续读。 */
        fun register(target: T): Boolean {
            if (closed.get()) {
                closeQuietly(target)
                return false
            }
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
