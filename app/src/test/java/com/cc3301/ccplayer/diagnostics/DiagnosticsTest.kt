package com.cc3301.ccplayer.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsTest {

    private val sink = MemoryDiagnosticsSink()
    private val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher())

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `关闭时不写入且不创建日志目录`() {
        val directory = File(temporaryFolder.root, "closed")
        val logger = Diagnostics(RotatingFileSink(directory), UnconfinedTestDispatcher())

        logger.log("应用启动")
        logger.logSync("应用退出")

        assertTrue(sink.lines.isEmpty())
        assertFalse(directory.exists())
    }

    @Test
    fun `开启后记录生命周期与播放状态迁移`() {
        diagnostics.enabled = true

        diagnostics.log("应用启动")
        diagnostics.log("播放状态: Idle -> Loading")

        assertEquals(listOf("应用启动", "播放状态: Idle -> Loading"), sink.lines)
    }

    @Test
    fun `未捕获异常堆栈同步写入`() {
        diagnostics.enabled = true

        diagnostics.logCrash(IllegalStateException("崩溃了"))

        val text = sink.lines.single()
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("崩溃了"))
        assertTrue(text.contains("DiagnosticsTest"))
    }

    @Test
    fun `关闭时惰性消息不求值`() {
        var evaluated = false

        diagnostics.log {
            evaluated = true
            "应用启动"
        }

        assertFalse(evaluated)
    }

    @Test
    fun `脱敏 URL userinfo、认证头、全角与 JSON 键`() {
        diagnostics.enabled = true

        diagnostics.log("地址 https://user:p@ss@nas/dav")
        diagnostics.log("Authorization: Basic dXNlcjpwYXNz")
        diagnostics.log("password = my secret value")
        diagnostics.log("token: abc def")
        diagnostics.log("密码：全角秘密")
        diagnostics.log("{\"password\":\"json-secret\"}")

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("https://nas/dav"))
        assertFalse(text.contains("p@ss"))
        assertFalse(text.contains("dXNlcjpwYXNz"))
        assertFalse(text.contains("my secret value"))
        assertFalse(text.contains("abc def"))
        assertFalse(text.contains("全角秘密"))
        assertFalse(text.contains("json-secret"))
    }

    @Test
    fun `导出为纯文本且不含密码`() {
        val logDirectory = temporaryFolder.newFolder("logs")
        val outputDirectory = temporaryFolder.newFolder("output")
        val fileSink = RotatingFileSink(logDirectory, maxFileBytes = 1024, maxFiles = 3)
        val logger = Diagnostics(fileSink, UnconfinedTestDispatcher()).apply { enabled = true }

        logger.log("连接源: SMB nas")
        logger.log("password=hunter2")
        logger.log("Authorization: Basic dXNlcjpwYXNz")

        val exported = DiagnosticsExporter(logDirectory, outputDirectory).export()

        assertEquals("txt", requireNotNull(exported).extension)
        val text = exported.readText(Charsets.UTF_8)
        assertTrue(text.contains("连接源: SMB nas"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("dXNlcjpwYXNz"))
    }

    @Test
    fun `导出从旧到新并排列文件`() {
        val logDirectory = temporaryFolder.newFolder("ordered")
        val outputDirectory = temporaryFolder.newFolder("ordered-output")
        File(logDirectory, "diagnostics.2.log").writeText("old\n", Charsets.UTF_8)
        File(logDirectory, "diagnostics.1.log").writeText("mid\n", Charsets.UTF_8)
        File(logDirectory, "diagnostics.log").writeText("new\n", Charsets.UTF_8)

        val exported = requireNotNull(DiagnosticsExporter(logDirectory, outputDirectory).export())
        val text = exported.readText(Charsets.UTF_8)

        assertTrue(text.indexOf("old") < text.indexOf("mid"))
        assertTrue(text.indexOf("mid") < text.indexOf("new"))
        assertTrue(text.contains("===== diagnostics.2.log ====="))
        assertTrue(text.contains("===== diagnostics.log ====="))
    }

    @Test
    fun `导出补正半行换行`() {
        val logDirectory = temporaryFolder.newFolder("half-line")
        val outputDirectory = temporaryFolder.newFolder("half-line-output")
        File(logDirectory, "diagnostics.1.log").writeText("mid\n", Charsets.UTF_8)
        File(logDirectory, "diagnostics.log").writeText("half", Charsets.UTF_8)

        val exported = requireNotNull(DiagnosticsExporter(logDirectory, outputDirectory).export())
        val text = exported.readText(Charsets.UTF_8)

        assertTrue(text.contains("mid\n===== diagnostics.log ====="))
        assertTrue(text.endsWith("half\n"))
    }

    @Test
    fun `没有日志时不生成导出文件并清理旧导出`() {
        val logDirectory = temporaryFolder.newFolder("empty")
        val outputDirectory = temporaryFolder.newFolder("empty-output")
        val oldExport = File(outputDirectory, "cc-player-diagnostics-old.txt").apply { writeText("旧") }

        val exported = DiagnosticsExporter(logDirectory, outputDirectory).export()

        assertNull(exported)
        assertFalse(oldExport.exists())
        assertTrue(outputDirectory.listFiles().orEmpty().isEmpty())
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

    @Test
    fun `单行超过上限时截断而不持续超限`() {
        val directory = temporaryFolder.newFolder("oversized")
        val fileSink = RotatingFileSink(directory, maxFileBytes = 16, maxFiles = 3)

        fileSink.write("x".repeat(100))

        val current = File(directory, RotatingFileSink.CURRENT_FILE_NAME)
        assertTrue(current.length() <= 16)
    }

    @Test
    fun `超长多字节行按字符边界截断`() {
        val directory = temporaryFolder.newFolder("utf8")
        val fileSink = RotatingFileSink(directory, maxFileBytes = 40, maxFiles = 3)

        fileSink.write("中".repeat(100))

        val text = File(directory, RotatingFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)
        assertTrue(text.isNotEmpty())
        assertFalse(text.contains('�'))
    }

    @Test
    fun `有内容时不清理旧导出`() {
        val logDirectory = temporaryFolder.newFolder("keep-old-logs")
        val outputDirectory = temporaryFolder.newFolder("keep-old-output")
        val oldExport = File(outputDirectory, "cc-player-diagnostics-old.txt").apply { writeText("旧") }
        File(logDirectory, RotatingFileSink.CURRENT_FILE_NAME).writeText("new\n", Charsets.UTF_8)

        assertTrue(DiagnosticsExporter(logDirectory, outputDirectory).export() != null)
        assertTrue(oldExport.exists())
    }

    @Test
    fun `并发写入不丢行`() {
        val directory = temporaryFolder.newFolder("concurrent")
        val fileSink = RotatingFileSink(directory, maxFileBytes = 1_000_000, maxFiles = 3)

        val threads = (0 until 4).map { thread ->
            Thread {
                repeat(50) { index -> fileSink.write("t$thread-$index") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val current = File(directory, RotatingFileSink.CURRENT_FILE_NAME)
        assertEquals(200, current.readLines().size)
    }

    @Test
    fun `重命名失败时删除源文件继续写入`() {
        val directory = temporaryFolder.newFolder("rename-failure")
        val fileSink = RotatingFileSink(
            directory = directory,
            maxFileBytes = 32,
            maxFiles = 3,
            moveFile = { _, _ -> false },
        )

        repeat(10) { index -> fileSink.write("line-$index") }

        val current = File(directory, RotatingFileSink.CURRENT_FILE_NAME)
        assertTrue(current.exists())
        assertTrue(current.length() <= 32)
        assertTrue(directory.listFiles().orEmpty().size <= 3)
    }
}
