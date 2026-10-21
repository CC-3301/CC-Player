package com.cc3301.ccplayer.diagnostics

import java.io.File

/**
 * 把轮转日志按从旧到新拼成一个纯文本文件，写到 [outputDirectory]。
 * 只读取诊断日志目录，导出目录之外的文件不参与分享；没有内容时不生成文件。
 */
class DiagnosticsExporter(
    private val logDirectory: File,
    private val outputDirectory: File,
    private val maxFiles: Int = Diagnostics.MAX_FILES,
) {

    fun export(): File? {
        outputDirectory.mkdirs()
        outputDirectory.listFiles()
            ?.filter { it.isFile && it.extension == "txt" }
            ?.forEach { it.delete() }

        val content = buildContent()
        if (content.isBlank()) return null

        val output = File(outputDirectory, "cc-player-diagnostics-${System.currentTimeMillis()}.txt")
        output.writeText(content, Charsets.UTF_8)
        return output
    }

    private fun buildContent(): String {
        val builder = StringBuilder()
        for (index in maxFiles - 1 downTo 1) {
            append(builder, File(logDirectory, "${RotatingFileSink.BASE_FILE_NAME}.$index.log"))
        }
        append(builder, File(logDirectory, RotatingFileSink.CURRENT_FILE_NAME))
        return builder.toString()
    }

    private fun append(builder: StringBuilder, file: File) {
        if (!file.isFile) return
        val text = file.readText(Charsets.UTF_8)
        if (text.isEmpty()) return
        if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
        builder.append("===== ${file.name} =====\n")
        builder.append(text)
        if (builder.last() != '\n') builder.append('\n')
    }
}
