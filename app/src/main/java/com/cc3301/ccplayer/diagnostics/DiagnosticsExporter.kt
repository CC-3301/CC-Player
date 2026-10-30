package com.cc3301.ccplayer.diagnostics

import java.io.File

/**
 * 把单文件会话日志拼成一个纯文本文件，写到 [outputDirectory]。
 * [systemInfo] 依次写在日志之前，用于带上应用版本、设备型号与系统版本。
 * 只读取诊断日志目录里的当前文件，导出目录之外的文件不参与分享；没有日志内容时不生成文件。
 */
class DiagnosticsExporter(
    private val logDirectory: File,
    private val outputDirectory: File,
    private val systemInfo: List<String> = emptyList(),
) {

    fun export(): File? {
        outputDirectory.mkdirs()
        val content = buildContent()
        if (content.isBlank()) {
            cleanOldExports()
            return null
        }

        val output = File(outputDirectory, "$EXPORT_PREFIX${System.currentTimeMillis()}.txt")
        output.writeText(content, Charsets.UTF_8)
        return output
    }

    /** 只清理本应用命名的旧导出，避免误删分享面板可能仍持有的其他文件。 */
    private fun cleanOldExports() {
        outputDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(EXPORT_PREFIX) && it.extension == "txt" }
            ?.forEach { it.delete() }
    }

    private fun buildContent(): String {
        val logs = StringBuilder()
        if (!append(logs, File(logDirectory, SessionFileSink.CURRENT_FILE_NAME))) return ""

        val builder = StringBuilder()
        appendSystemInfo(builder)
        builder.append(logs)
        return builder.toString()
    }

    private fun appendSystemInfo(builder: StringBuilder) {
        if (systemInfo.isEmpty()) return
        builder.append("===== 系统信息 =====\n")
        systemInfo.forEach { line -> builder.append(line).append('\n') }
    }

    /** 追加日志文件，返回其中是否存在非空白内容。 */
    private fun append(builder: StringBuilder, file: File): Boolean {
        if (!file.isFile) return false
        val text = file.readText(Charsets.UTF_8)
        if (text.isEmpty()) return false
        if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
        builder.append("===== ${file.name} =====\n")
        builder.append(text)
        if (builder.last() != '\n') builder.append('\n')
        return text.isNotBlank()
    }

    private companion object {
        const val EXPORT_PREFIX = "cc-player-diagnostics-"
    }
}
