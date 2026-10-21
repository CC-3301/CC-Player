package com.cc3301.ccplayer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {

    private val sink = MemoryDiagnosticsSink()
    private val diagnostics = Diagnostics(sink)

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `关闭时不写入`() {
        diagnostics.log("应用启动")
        diagnostics.log(IllegalStateException("关闭状态"))

        assertTrue(sink.lines.isEmpty())
    }

    @Test
    fun `开启后记录生命周期与播放状态迁移`() {
        diagnostics.enabled = true

        diagnostics.log("应用启动")
        diagnostics.log("播放状态: Idle -> Loading")

        assertEquals(listOf("应用启动", "播放状态: Idle -> Loading"), sink.lines)
    }

    @Test
    fun `异常堆栈写入`() {
        diagnostics.enabled = true

        diagnostics.log(IllegalStateException("崩溃了"))

        val text = sink.lines.single()
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("崩溃了"))
        assertTrue(text.contains("DiagnosticsTest"))
    }

    @Test
    fun `导出为纯文本且不含密码`() {
        val logDirectory = temporaryFolder.newFolder("logs")
        val outputDirectory = temporaryFolder.newFolder("output")
        val fileSink = RotatingFileSink(logDirectory, maxFileBytes = 1024, maxFiles = 3)
        val logger = Diagnostics(fileSink).apply { enabled = true }

        logger.log("连接源: SMB nas")
        logger.log("password=hunter2")
        logger.log("Authorization: Bearer abc123")

        val exported = DiagnosticsExporter(logDirectory, outputDirectory).export()

        assertEquals("txt", exported.extension)
        val text = exported.readText(Charsets.UTF_8)
        assertTrue(text.contains("连接源: SMB nas"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("abc123"))
    }

    @Test
    fun `单文件达到上限后轮转且最多保留三个`() {
        val directory = temporaryFolder.newFolder("rotation")
        val fileSink = RotatingFileSink(directory, maxFileBytes = 64, maxFiles = 3)

        repeat(20) { index -> fileSink.write("line-$index-1234567890") }

        val files = directory.listFiles().orEmpty().filter { it.isFile }
        assertEquals(3, files.size)
        assertTrue(files.all { it.length() <= 64 })
    }
}
