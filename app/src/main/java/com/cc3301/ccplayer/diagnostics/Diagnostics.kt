package com.cc3301.ccplayer.diagnostics

/** 诊断日志出口；生产环境写轮转文件，测试注入内存实现。 */
interface DiagnosticsSink {
    fun write(message: String)
}

/** 诊断日志接缝：关闭时不向外写出任何内容。 */
interface DiagnosticsLog {
    fun log(message: String)
}

/** 不写日志的实现，供不需要诊断的构造使用。 */
object NoDiagnosticsLog : DiagnosticsLog {
    override fun log(message: String) = Unit
}

/**
 * 诊断日志：是否写入由设置开关控制，写入前隐去密码、凭据与认证头。
 */
class Diagnostics(private val sink: DiagnosticsSink) : DiagnosticsLog {

    @Volatile
    var enabled: Boolean = false

    override fun log(message: String) {
        if (!enabled) return
        runCatching { sink.write(redactSecrets(message)) }
    }

    /** 未捕获异常的堆栈。 */
    fun log(throwable: Throwable) {
        log("未捕获异常:\n${throwable.stackTraceToString()}")
    }

    companion object {
        /** 日志文件所在目录名（files 与 cache 下同名）。 */
        const val DIRECTORY_NAME = "diagnostics"

        /** 单个日志文件的上限。 */
        const val MAX_FILE_BYTES = 2L * 1024L * 1024L

        /** 最多保留的日志文件数。 */
        const val MAX_FILES = 3

        private val SECRET_PATTERN = Regex(
            "(?i)\\b(password|passwd|pwd|authorization|token|secret|密码)\\b\\s*[=:]\\s*(?:Bearer\\s+)?\\S+",
        )

        /** 把可能带出的密码、令牌与认证头整段替换掉。 */
        fun redactSecrets(message: String): String = SECRET_PATTERN.replace(message) { match ->
            "${match.groupValues[1]}=[已隐藏]"
        }
    }
}
