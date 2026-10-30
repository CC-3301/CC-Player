package com.cc3301.ccplayer.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 内存日志出口：单测用它断言写入了什么。 */
class MemoryDiagnosticsSink : DiagnosticsSink {

    private val lock = Any()
    private val written = mutableListOf<String>()

    val lines: List<String> get() = synchronized(lock) { written.toList() }

    override fun write(message: String) {
        synchronized(lock) { written += message }
    }
}

/**
 * 单文件会话日志：当前文件为 `diagnostics.log`，只保留最近两次会话。
 * 每个进程第一次写入先追加一条会话标记；文件里已有两次会话时，先截掉最旧的一次再追加本次标记。
 * 单文件超过 [maxFileBytes] 时同样从最旧会话开始截；只剩本次会话后不再截断，避免写残当前会话。
 */
class SessionFileSink(
    private val directory: File,
    private val maxFileBytes: Long = Diagnostics.MAX_FILE_BYTES,
) : DiagnosticsSink {

    private val lock = Any()
    private val limit = maxFileBytes.coerceAtLeast(1L)

    /** 本进程是否已追加会话标记；进程内只追加一次。 */
    private var sessionStarted = false

    /** 文件里当前的会话数；没有标记的旧内容也算一次会话。 */
    private var sessionsInFile = 0

    override fun write(message: String) {
        synchronized(lock) {
            val current = File(directory, CURRENT_FILE_NAME)
            directory.mkdirs()
            if (!sessionStarted) beginSession(current)
            if (current.length() >= limit && sessionsInFile > 1) {
                keepLatestSession(current)
                sessionsInFile = 1
            }
            val line = "${timestamp()} $message\n"
            FileOutputStream(current, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        }
    }

    /** 新会话：先丢掉本次之前的旧会话，只留最近一次，再追加本次标记。 */
    private fun beginSession(current: File) {
        sessionStarted = true
        val existing = if (current.isFile) current.readText(Charsets.UTF_8) else ""
        val kept = if (existing.isBlank()) "" else latestSession(existing)
        val content = kept + sessionMarkerLine()
        current.writeText(content, Charsets.UTF_8)
        sessionsInFile = countSessions(content).coerceAtLeast(1)
    }

    /** 从最后一个会话标记处取到文件末尾；没有标记时整份当作上次会话。 */
    private fun latestSession(text: String): String {
        val last = text.lastIndexOf(SESSION_MARKER_PREFIX)
        return if (last <= 0) text else text.substring(last)
    }

    /** 单文件超限：只留本次会话。 */
    private fun keepLatestSession(current: File) {
        val text = current.readText(Charsets.UTF_8)
        val last = text.lastIndexOf(SESSION_MARKER_PREFIX)
        if (last > 0) current.writeText(text.substring(last), Charsets.UTF_8)
    }

    private fun countSessions(text: String): Int =
        if (text.isEmpty()) 0 else text.split(SESSION_MARKER_PREFIX).size - 1

    private fun sessionMarkerLine(): String = "$SESSION_MARKER_PREFIX${timestamp()} =====\n"

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    companion object {
        const val BASE_FILE_NAME = "diagnostics"

        const val CURRENT_FILE_NAME = "$BASE_FILE_NAME.log"

        /** 会话标记行前缀：清理与截断都按它识别会话边界。 */
        const val SESSION_MARKER_PREFIX = "===== 会话开始 "
    }
}
