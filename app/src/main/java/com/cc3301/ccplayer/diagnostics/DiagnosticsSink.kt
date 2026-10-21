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
 * 轮转文件出口：当前文件为 `diagnostics.log`，轮转文件依次为 `diagnostics.1.log`、
 * `diagnostics.2.log`；单文件达到 [maxFileBytes] 时切新文件，超过 [maxFiles] 的删最旧。
 * 目录与文件在第一次写入时才创建，关闭状态不会留下日志文件。
 */
class RotatingFileSink(
    private val directory: File,
    private val maxFileBytes: Long = Diagnostics.MAX_FILE_BYTES,
    private val maxFiles: Int = Diagnostics.MAX_FILES,
) : DiagnosticsSink {

    private val lock = Any()

    override fun write(message: String) {
        synchronized(lock) {
            val line = "${timestamp()} $message\n".toByteArray(Charsets.UTF_8)
            val current = File(directory, CURRENT_FILE_NAME)
            if (current.length() > 0 && current.length() + line.size > maxFileBytes) rotate()
            directory.mkdirs()
            FileOutputStream(File(directory, CURRENT_FILE_NAME), true).use { it.write(line) }
        }
    }

    private fun rotate() {
        if (maxFiles <= 1) {
            File(directory, CURRENT_FILE_NAME).delete()
            return
        }
        File(directory, rotatedFileName(maxFiles - 1)).delete()
        for (index in maxFiles - 2 downTo 1) {
            val source = File(directory, rotatedFileName(index))
            if (source.exists()) source.renameTo(File(directory, rotatedFileName(index + 1)))
        }
        val current = File(directory, CURRENT_FILE_NAME)
        if (current.exists()) current.renameTo(File(directory, rotatedFileName(1)))
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun rotatedFileName(index: Int): String = "$BASE_FILE_NAME.$index.log"

    companion object {
        const val BASE_FILE_NAME = "diagnostics"
        const val CURRENT_FILE_NAME = "$BASE_FILE_NAME.log"
    }
}
