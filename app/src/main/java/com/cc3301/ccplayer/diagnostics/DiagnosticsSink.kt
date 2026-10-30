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
 * 单文件有 [maxFileBytes] 硬上限：超出先丢最旧会话；只剩本次会话仍超限时，
 * 从本次会话最早的日志开始丢、保留最近的。每条日志行自成一行，放不下一整行时丢弃该行。
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

    /** 已经存在日志文件：已判定写入时据此决定是否允许首次建文件。 */
    override val hasContent: Boolean
        get() = File(directory, CURRENT_FILE_NAME).isFile

    override fun write(message: String) {
        synchronized(lock) {
            val current = File(directory, CURRENT_FILE_NAME)
            directory.mkdirs()
            if (!sessionStarted) beginSession(current)
            val line = "${timestamp()} $message\n".toByteArray(Charsets.UTF_8)
            // 一整行都放不下：丢弃这一行，写半行会让下一行粘上来。
            if (line.size > limit) return
            if (current.length() + line.size > limit && sessionsInFile > 1) {
                // 先丢最旧会话；只剩本次会话仍放不下时才动本次会话。
                keepLatestSession(current)
                sessionsInFile = 1
            }
            if (current.length() + line.size > limit) dropOldestLines(current, line.size.toLong())
            // 裁剪后仍放不下同样丢弃整行，不写半行。
            if (current.length() + line.size > limit) return
            FileOutputStream(current, true).use { it.write(line) }
        }
    }

    /**
     * 只剩本次会话仍超限：保留本次会话标记，从最早的日志行开始丢、保留最近的。
     * 重写结果按完整行组织，始终以换行结尾。
     */
    private fun dropOldestLines(current: File, incomingBytes: Long) {
        val text = current.readText(Charsets.UTF_8)
        val markerAt = markerStarts(text).lastOrNull() ?: 0
        val markerEnd = text.indexOf('\n', markerAt).let { if (it < 0) text.length else it + 1 }
        val marker = text.substring(markerAt, markerEnd)
        val lines = text.substring(markerEnd).trimEnd('\n').split('\n').filter { it.isNotEmpty() }
        val budget = limit - incomingBytes - marker.toByteArray(Charsets.UTF_8).size
        // 一次多丢一些：留出余量后不必每写一行都重写整个文件。
        val target = budget * 3L / 4L
        val kept = ArrayDeque<String>()
        var used = 0L
        for (index in lines.indices.reversed()) {
            val line = lines[index]
            val size = line.toByteArray(Charsets.UTF_8).size + 1L
            if (kept.isNotEmpty() && used + size > target) break
            kept.addFirst(line)
            used += size
        }
        val body = if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n"
        current.writeText(marker + body, Charsets.UTF_8)
    }

    /** 新会话：先丢掉本次之前的旧会话，只留最近一次，再追加本次标记。 */
    private fun beginSession(current: File) {
        sessionStarted = true
        val existing = if (current.isFile) current.readText(Charsets.UTF_8) else ""
        var kept = if (existing.isBlank()) "" else latestSession(existing)
        // 旧版本文件可能不以换行结尾：补一个，避免标记粘在最后一行上。
        if (kept.isNotEmpty() && !kept.endsWith("\n")) kept += "\n"
        val content = kept + sessionMarkerLine()
        current.writeText(content, Charsets.UTF_8)
        sessionsInFile = countSessions(content).coerceAtLeast(1)
    }

    /** 从最后一个行首会话标记处取到文件末尾；没有标记时整份当作上次会话。 */
    private fun latestSession(text: String): String {
        val last = markerStarts(text).lastOrNull() ?: return text
        return if (last <= 0) text else text.substring(last)
    }

    /** 单文件超限：只留本次会话。 */
    private fun keepLatestSession(current: File) {
        val text = current.readText(Charsets.UTF_8)
        val last = markerStarts(text).lastOrNull() ?: return
        if (last > 0) current.writeText(text.substring(last), Charsets.UTF_8)
    }

    /**
     * 行首会话标记的位置。
     * 只认行首：日志正文里出现同样的字样不当作会话边界。
     */
    private fun markerStarts(text: String): List<Int> {
        val starts = mutableListOf<Int>()
        val prefixLength = SESSION_MARKER_PREFIX.length
        var index = 0
        while (index <= text.length - prefixLength) {
            val at = text.indexOf(SESSION_MARKER_PREFIX, index)
            if (at < 0) break
            if (at == 0 || text[at - 1] == '\n') starts += at
            index = at + prefixLength
        }
        return starts
    }

    /** 会话数：行首标记的个数；标记之前还有内容时，那段无标记的旧日志也算一次会话。 */
    private fun countSessions(text: String): Int {
        if (text.isBlank()) return 0
        val starts = markerStarts(text)
        return if (starts.isEmpty()) 1 else starts.size + if (starts.first() > 0) 1 else 0
    }

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
