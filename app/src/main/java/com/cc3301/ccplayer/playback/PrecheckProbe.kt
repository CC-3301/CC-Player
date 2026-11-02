package com.cc3301.ccplayer.playback

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 切集预检的读取预算：打开句柄读 1 个字节，到点按不可读收尾。
 *
 * 计时只有一处：[timeoutMillis] 到点时把结果判为不可读，并关掉已打开的句柄，
 * 让不响应取消的阻塞读退出；句柄无论成败都由这里关闭。
 * 超时与读失败都只报「不可读」，由调用方按耗时区分。
 */
internal class PrecheckProbe(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS,
) {

    /**
     * [open] 打开目标读取句柄（打不开返回 null），[read] 读 1 个字节。
     * 返回是否可读；返回前句柄已关闭。
     */
    suspend fun <T : Closeable> probe(open: suspend () -> T?, read: (T) -> Boolean): Boolean {
        val opened = AtomicReference<T?>(null)
        val outcome = CompletableDeferred<Boolean>()
        val reader = scope.launch(Dispatchers.IO) {
            val result = try {
                val target = open()
                if (target == null) {
                    false
                } else {
                    opened.set(target)
                    try {
                        read(target)
                    } finally {
                        opened.compareAndSet(target, null)
                        runCatching { target.close() }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // 打不开、读失败、到点关闭句柄引起的读异常都按不可读收尾。
                false
            }
            outcome.complete(result)
        }
        val timer = scope.launch(Dispatchers.IO) {
            delay(timeoutMillis)
            // 唯一到点的地方：先判不可读，再关句柄把阻塞读打断。
            outcome.complete(false)
            opened.get()?.let { runCatching { it.close() } }
        }
        return try {
            outcome.await()
        } finally {
            timer.cancel()
            reader.cancel()
        }
    }
}
