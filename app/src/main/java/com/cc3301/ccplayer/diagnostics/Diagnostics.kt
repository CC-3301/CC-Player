package com.cc3301.ccplayer.diagnostics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** 诊断日志出口；生产环境写轮转文件，测试注入内存实现。 */
interface DiagnosticsSink {
    fun write(message: String)
}

/** 诊断日志接缝：关闭时不向外写出任何内容。 */
interface DiagnosticsLog {
    val enabled: Boolean

    fun log(message: String)

    /** 惰性消息：关闭时不拼接字符串。 */
    fun log(message: () -> String) {
        if (enabled) log(message())
    }

    /** 诊断由关闭变为开启时回调一次；返回注销句柄。 */
    fun onEnabled(listener: () -> Unit): () -> Unit = {}

    /**
     * 写出已经判定过开关的日志：写入时不再复查开关。
     * 补写暂存日志时用，避免与并发关停交错时把已取出的行丢掉。
     */
    fun logAccepted(message: String) {
        log(message)
    }
}

/** 不写日志的实现，供不需要诊断的构造使用。 */
object NoDiagnosticsLog : DiagnosticsLog {
    override val enabled: Boolean = false

    override fun log(message: String) = Unit
}

/**
 * 诊断日志：是否写入由设置开关控制，写入前隐去密码、凭据与认证头。
 * 普通日志投递到单线程 IO 串行写入；崩溃与退出路径提供同步写入。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Diagnostics(
    private val sink: DiagnosticsSink,
    dispatcher: CoroutineDispatcher = SINGLE_IO,
) : DiagnosticsLog {

    @Volatile
    override var enabled: Boolean = false
        set(value) {
            val wasEnabled = field
            field = value
            if (value && !wasEnabled) notifyEnabled()
        }

    private val enabledListeners = CopyOnWriteArrayList<() -> Unit>()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun onEnabled(listener: () -> Unit): () -> Unit {
        enabledListeners += listener
        if (enabled) runCatching(listener)
        return { enabledListeners.remove(listener) }
    }

    private fun notifyEnabled() {
        enabledListeners.toList().forEach { listener -> runCatching { listener() } }
    }

    override fun log(message: String) {
        if (!enabled) return
        scope.launch { runCatching { sink.write(redactSecrets(message)) } }
    }

    /** 已经判定开关打开的消息直接写出：与并发关停交错时也不复查，补写不丢行。 */
    override fun logAccepted(message: String) {
        scope.launch { runCatching { sink.write(redactSecrets(message)) } }
    }

    /** 同步写入，供应用退出等进程可能立即结束的路径使用。 */
    fun logSync(message: String) {
        if (!enabled) return
        runCatching { sink.write(redactSecrets(message)) }
    }

    /** 同步写入未捕获异常；是否启用由崩溃处理器先行判定。 */
    fun logCrash(throwable: Throwable) {
        runCatching {
            sink.write(redactSecrets("未捕获异常:\n${throwable.stackTraceToString()}"))
        }
    }

    companion object {
        /** 日志文件所在目录名（files 与 cache 下同名）。 */
        const val DIRECTORY_NAME = "diagnostics"

        /** 单个日志文件的上限。 */
        const val MAX_FILE_BYTES = 2L * 1024L * 1024L

        private val SINGLE_IO = Dispatchers.IO.limitedParallelism(1)

        private val USERINFO_PATTERN = Regex("(?i)([a-z][a-z0-9+.-]*://)[^/\\s]*@")

        private val SECRET_PATTERN = Regex(
            "(?i)(?<![A-Za-z0-9_])[\"'“”]?(password|passwd|pwd|authorization|token|secret|密码)" +
                "[\"'“”]?\\s*[:=：＝]\\s*[^\\n]*",
        )

        /** 去掉 URL 中的 userinfo，并把密码、令牌与整条认证头隐去。 */
        fun redactSecrets(message: String): String = message
            .replace(USERINFO_PATTERN, "$1")
            .replace(SECRET_PATTERN) { match -> "${match.groupValues[1]}=[已隐藏]" }
    }
}
