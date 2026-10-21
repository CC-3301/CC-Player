package com.cc3301.ccplayer

import android.app.Application
import com.cc3301.ccplayer.diagnostics.CrashLogger
import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.RotatingFileSink
import com.cc3301.ccplayer.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
            settings.diagnosticsEnabled
                .retry(3) {
                    delay(500)
                    true
                }
                .distinctUntilChanged()
                .catch { diagnostics.enabled = false }
                .collect { enabled ->
                    diagnostics.enabled = enabled
                    if (!initialized) {
                        initialized = true
                        if (enabled) diagnostics.logSync("应用启动")
                    } else if (enabled) {
                        diagnostics.logSync("诊断日志已开启")
                    }
                }
        }
    }

    /** 崩溃路径同步读取开关，避免启动后立即崩溃时开关还没读到。 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val crashLogger = CrashLogger(
            isEnabled = { runBlocking { settings.diagnosticsEnabled.first() } },
            write = diagnostics::logCrash,
        )
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashLogger.handle(thread, throwable, previous)
        }
    }
}
