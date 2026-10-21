package com.cc3301.ccplayer.diagnostics

import java.io.BufferedWriter
import java.io.File

/**
 * 把轮转日志按从旧到新拼成一个纯文本文件，写到 [outputDirectory]。
 * 只读取诊断日志目录，导出目录之外的文件不参与分享。
 */
class DiagnosticsExporter(
    private val logDirectory: File,
    private val outputDirectory: File,
    private val maxFiles: Int = Diagnostics.MAX_FILES,
) {

    fun export(): File {
        outputDirectory.mkdirs()
        val output = File(outputDirectory, "cc-player-diagnostics-${System.currentTimeMillis()}.txt")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (index in maxFiles - 1 downTo 1) {
                appendRotated(writer, index)
            }
            appendCurrent(writer)
        }
        return output
    }

    private fun appendRotated(writer: BufferedWriter, index: Int) {
        val file = File(logDirectory, "${RotatingFileSink.BASE_FILE_NAME}.$index.log")
        if (file.isFile) writer.append(file.readText(Charsets.UTF_8))
    }

    private fun appendCurrent(writer: BufferedWriter) {
        val file = File(logDirectory, RotatingFileSink.CURRENT_FILE_NAME)
        if (file.isFile) writer.append(file.readText(Charsets.UTF_8))
    }
}
