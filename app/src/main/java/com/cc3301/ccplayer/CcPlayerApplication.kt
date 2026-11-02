package com.cc3301.ccplayer

import android.app.Application
import com.cc3301.ccplayer.diagnostics.CrashLogger
import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.SessionFileSink
import com.cc3301.ccplayer.library.CachedMediaPreviewSource
import com.cc3301.ccplayer.library.MediaPreviewHolder
import com.cc3301.ccplayer.library.MediaPreviewSource
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.network.DataStoreBookmarkStore
import com.cc3301.ccplayer.network.EncryptedCredentialStore
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.RoutingMediaReads
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.smb.SmbJConnector
import com.cc3301.ccplayer.smb.SmbLocation
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbMediaReads
import com.cc3301.ccplayer.webdav.OkHttpWebDavTransport
import com.cc3301.ccplayer.webdav.WebDavAddress
import com.cc3301.ccplayer.webdav.WebDavLocator
import com.cc3301.ccplayer.webdav.WebDavMediaReads
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

/** 应用进程：持有诊断日志与进程内唯一的预览源，并把 Java 未捕获异常写进日志后交回默认处理器。 */
class CcPlayerApplication : Application() {

    lateinit var diagnostics: Diagnostics
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settings: SettingsRepository by lazy { SettingsRepository.create(this) }

    /** 网络随机读：封面与切集预检共用一份，避免各自再建一套会话池。 */
    val networkReads: NetworkMediaReads by lazy { createNetworkReads() }

    private val previewHolder = MediaPreviewHolder {
        // 后台范围用进程级 scope：时长补读等任务跟随应用，不随界面重建。
        CachedMediaPreviewSource.create(this, networkReads, diagnostics, backgroundScope = scope)
    }

    /** 缩略图预览源：整个进程只有一份，转屏与界面重建后复用同一个抽帧许可。 */
    val mediaPreview: MediaPreviewSource get() = previewHolder.instance

    /** 随机读直接走各协议，不建回环代理，也不参与播放代理的释放。 */
    private fun createNetworkReads(): NetworkMediaReads {
        val sources = NetworkSourceRepository(
            store = DataStoreBookmarkStore.create(this),
            credentials = EncryptedCredentialStore.create(this),
        )
        val webDavReads = WebDavMediaReads(OkHttpWebDavTransport(), WebDavLocator(sources))
        return RoutingMediaReads(
            mapOf(
                SmbLocation.SCHEME to SmbMediaReads(
                    // 预览读的协商结果记一次日志：方言决定块大小（SMB2.0.2 只有 64 KiB）。
                    SmbJConnector(diagnostics = diagnostics),
                    SmbLocator(sources),
                ),
                WebDavAddress.SCHEME_HTTP to webDavReads,
                WebDavAddress.SCHEME_HTTPS to webDavReads,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics(SessionFileSink(File(filesDir, Diagnostics.DIRECTORY_NAME)))
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
