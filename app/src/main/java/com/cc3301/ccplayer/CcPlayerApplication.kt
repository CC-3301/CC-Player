package com.cc3301.ccplayer

import android.app.Application
import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.RotatingFileSink
import com.cc3301.ccplayer.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 应用进程：持有诊断日志，并把 Java 未捕获异常写进日志后交回默认处理器。 */
class CcPlayerApplication : Application() {

    lateinit var diagnostics: Diagnostics
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics(RotatingFileSink(File(filesDir, Diagnostics.DIRECTORY_NAME)))
        installCrashHandler()
        scope.launch {
            var initialized = false
            SettingsRepository.create(this@CcPlayerApplication).diagnosticsEnabled
                .distinctUntilChanged()
                .collect { enabled ->
                    diagnostics.enabled = enabled
                    if (!initialized) {
                        initialized = true
                        if (enabled) diagnostics.log("应用启动")
                    } else if (enabled) {
                        diagnostics.log("诊断日志已开启")
                    }
                }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            diagnostics.log(throwable)
            previous?.uncaughtException(thread, throwable) ?: throw throwable
        }
    }
}
