package com.cc3301.ccplayer.diagnostics

/**
 * 全局未捕获异常处理：先尝试同步写日志，无论日志是否成功都交回默认处理器。
 */
class CrashLogger(
    private val isEnabled: () -> Boolean,
    private val write: (Throwable) -> Unit,
) {

    fun handle(thread: Thread, throwable: Throwable, previous: Thread.UncaughtExceptionHandler?) {
        try {
            if (isEnabled()) write(throwable)
        } catch (_: Throwable) {
            // 日志失败不能阻断崩溃流程。
        } finally {
            previous?.uncaughtException(thread, throwable) ?: throw throwable
        }
    }
}
