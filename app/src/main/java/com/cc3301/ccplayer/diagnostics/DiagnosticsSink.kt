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
 * 从本次会话最早的日志开始丢、保留最近的，文件不会无限增长。
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
            var line = "${timestamp()} $message\n".toByteArray(Charsets.UTF_8)
            if (line.size > limit) line = truncate(line, limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (current.length() + line.size > limit && sessionsInFile > 1) {
                // 先丢最旧会话；只剩本次会话仍放不下时才动本次会话。
                keepLatestSession(current)
                sessionsInFile = 1
            }
            if (current.length() + line.size > limit) dropOldestLines(current, line.size.toLong())
            val remaining = (limit - current.length()).coerceAtLeast(0L).toInt()
            val toWrite = if (line.size > remaining) truncate(line, remaining) else line
            if (toWrite.isEmpty()) return
            FileOutputStream(current, true).use { it.write(toWrite) }
        }
    }

    /** 只剩本次会话仍超限：保留会话标记，从最早的日志行开始丢，给新行留位置。 */
    private fun dropOldestLines(current: File, incomingBytes: Long) {
        val text = current.readText(Charsets.UTF_8)
        val markerEnd = text.indexOf('\n').let { if (it < 0) text.length else it + 1 }
        val marker = text.substring(0, markerEnd)
        val body = text.substring(markerEnd).trimEnd('\n')
        val budget = limit - incomingBytes - marker.toByteArray(Charsets.UTF_8).size
        // 一次多丢一些：留出余量后不必每写一行都重写整个文件。
        val target = budget * 3L / 4L
        val kept = ArrayDeque<String>()
        var used = 0L
        var index = body.length
        // 从末尾往前按行收集，超预算即停：只留最近的日志。
        while (index > 0) {
            val start = body.lastIndexOf('\n', index - 1)
            val line = body.substring(start + 1, index)
            val size = line.toByteArray(Charsets.UTF_8).size + 1L
            if (kept.isNotEmpty() && used + size > target) break
            kept.addFirst(line)
            used += size
            index = if (start < 0) 0 else start
        }
        current.writeText(marker + kept.joinToString("\n"), Charsets.UTF_8)
    }

    /** 截到 [max] 字节以内，并回退到字符边界，避免写出半个字符。 */
    private fun truncate(bytes: ByteArray, max: Int): ByteArray {
        if (bytes.size <= max) return bytes
        var end = max.coerceAtLeast(0)
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
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
