package com.cc3301.ccplayer

import android.app.Application
import com.cc3301.ccplayer.diagnostics.CrashLogger
import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.RotatingFileSink
import com.cc3301.ccplayer.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** 应用进程：持有诊断日志，并把 Java 未捕获异常写进日志后交回默认处理器。 */
class CcPlayerApplication : Application() {

    lateinit var diagnostics: Diagnostics
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settings: SettingsRepository by lazy { SettingsRepository.create(this) }

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics(RotatingFileSink(File(filesDir, Diagnostics.DIRECTORY_NAME)))
        installCrashHandler()
        scope.launch {
            var initialized = false
            var backoffMillis = INITIAL_RETRY_DELAY_MILLIS
            while (isActive) {
                try {
                    settings.diagnosticsEnabled
                        .distinctUntilChanged()
                        .collect { enabled ->
                            backoffMillis = INITIAL_RETRY_DELAY_MILLIS
                            diagnostics.enabled = enabled
                            if (!initialized) {
                                initialized = true
                                if (enabled) diagnostics.logSync("应用启动")
                            } else if (enabled) {
                                diagnostics.logSync("诊断日志已开启")
                            }
                        }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // 读失败时按默认关闭处理，退避后重新采集。
                    diagnostics.enabled = false
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
                }
            }
        }
    }

    /**
     * 崩溃路径同步读取开关，避免启动后立即崩溃时开关还没读到。
     * 读取设置可能卡住，超时回退到内存中的标志；日志失败也由 [CrashLogger] 兜底委托。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val crashLogger = CrashLogger(
            isEnabled = {
                runBlocking {
                    withTimeoutOrNull(CRASH_SETTING_TIMEOUT_MILLIS) {
                        settings.diagnosticsEnabled.first()
                    }
                } ?: diagnostics.enabled
            },
            write = diagnostics::logCrash,
        )
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashLogger.handle(thread, throwable, previous)
        }
    }

    private companion object {
        const val CRASH_SETTING_TIMEOUT_MILLIS = 500L
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
    }
}
