package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 切集预检的读取预算：打开句柄读 1 个字节，到点按不可读收尾。
 *
 * 句柄只有一个所有者（[HandleSlot]）：open 一返回就登记，无论先到的是读完、到点还是取消，
 * 都从这里关；打开得晚的句柄一登记就被关掉，不再继续读。
 * 结论只有一个出口：成功、读失败、到点、异常都走同一个收尾入口。
 * 到点任务挂在独立作用域上：调用方取消不会连着把关句柄这一步一起取消。
 */
internal class PrecheckProbe(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS,
) {

    /** 到点任务的执行者：不挂在调用方作用域上，调用方取消不会取消关句柄这一步。 */
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [open] 打开目标读取句柄（打不开返回 null），[read] 读 1 个字节。
     * 返回是否可读；返回前句柄已进入唯一的关闭入口。
     */
    suspend fun <T : Closeable> probe(open: suspend () -> T?, read: (T) -> Boolean): Boolean {
        val handles = HandleSlot<T>()
        val readResult = AtomicReference<Boolean?>(null)
        val outcome = CompletableDeferred<Boolean>()

        // 唯一收尾入口：先交还句柄（最多关一次），再给结论；重复调用无副作用。
        fun finish(readable: Boolean) {
            handles.close()
            outcome.complete(readable)
        }

        scope.launch(Dispatchers.IO) {
            val target = try {
                open()
            } catch (cancellation: CancellationException) {
                finish(false)
                throw cancellation
            } catch (failure: Throwable) {
                null
            }
            if (target == null) {
                finish(false)
                return@launch
            }
            // 预检已经收尾时，晚到的句柄在登记处立即关掉，不再继续读。
            if (!handles.register(target)) return@launch
            val readable = try {
                read(target)
            } catch (cancellation: CancellationException) {
                finish(false)
                throw cancellation
            } catch (failure: Throwable) {
                false
            }
            // 先记下读的结果再上报：到点任务据此不把已经读成功的目标判成不可读。
            readResult.set(readable)
            finish(readable)
        }

        // 到点任务不属于调用方作用域：连点、退出播放页、服务销毁都不会取消关句柄这一步。
        timerScope.launch {
            delay(timeoutMillis)
            finish(readResult.get() == true)
        }

        return outcome.await()
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

        /** 关闭入口；句柄已被取走时直接跳过，重复调用不会关第二次。 */
        fun close() {
            closed.set(true)
            handle.getAndSet(null)?.let(::closeQuietly)
        }
    }
}

private fun closeQuietly(target: Closeable) {
    runCatching { target.close() }
}
