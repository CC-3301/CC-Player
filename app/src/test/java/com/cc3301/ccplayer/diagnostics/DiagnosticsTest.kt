package com.cc3301.ccplayer.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
        val logger = Diagnostics(SessionFileSink(directory), UnconfinedTestDispatcher())

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
    fun `开启诊断时通知一次补写观察者`() {
        var notified = 0
        diagnostics.onEnabled { notified += 1 }

        diagnostics.enabled = true
        diagnostics.enabled = true

        assertEquals(1, notified)
    }

    @Test
    fun `诊断已开启后注册观察者立即通知`() {
        diagnostics.enabled = true
        var notified = 0

        diagnostics.onEnabled { notified += 1 }

        assertEquals(1, notified)
    }

    @Test
    fun `注销后不再通知观察者`() {
        var notified = 0
        val remove = diagnostics.onEnabled { notified += 1 }

        remove()
        diagnostics.enabled = true

        assertEquals(0, notified)
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
    fun `已判定写出的日志不因开关关掉而丢行`() {
        diagnostics.enabled = true
        diagnostics.enabled = false

        // 内存出口视为已有日志内容：已判定写出的行照样落盘，不会因为开关刚关掉就丢。
        diagnostics.logAccepted("补写")

        assertEquals(listOf("补写"), sink.lines)
    }

    @Test
    fun `判定之后立即关闭开关时不新建日志文件`() {
        val directory = temporaryFolder.newFolder("closed-after-accept")
        val scheduler = TestCoroutineScheduler()
        val logger = Diagnostics(SessionFileSink(directory), StandardTestDispatcher(scheduler))

        // 判定通过后开关立刻关掉，写入排队到之后执行：还没有日志文件就不能建。
        logger.enabled = true
        logger.logAccepted("补写")
        logger.enabled = false
        scheduler.advanceUntilIdle()

        assertFalse(File(directory, SessionFileSink.CURRENT_FILE_NAME).exists())
    }

    @Test
    fun `判定之后关闭开关时已有日志文件仍追加`() {
        val directory = temporaryFolder.newFolder("append-after-accept")
        val fileSink = SessionFileSink(directory)
        fileSink.write("先有一行")
        val scheduler = TestCoroutineScheduler()
        val logger = Diagnostics(fileSink, StandardTestDispatcher(scheduler))

        logger.enabled = true
        logger.logAccepted("补写")
        logger.enabled = false
        scheduler.advanceUntilIdle()

        val text = File(directory, SessionFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)
        assertTrue(text.contains("补写"))
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
        val fileSink = SessionFileSink(logDirectory, maxFileBytes = 1024)
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
    fun `导出包含应用版本设备型号与系统版本`() {
        val logDirectory = temporaryFolder.newFolder("system-info")
        val outputDirectory = temporaryFolder.newFolder("system-info-output")
        File(logDirectory, "diagnostics.log").writeText("应用启动\n", Charsets.UTF_8)
        val exporter = DiagnosticsExporter(
            logDirectory = logDirectory,
            outputDirectory = outputDirectory,
            systemInfo = listOf(
                "应用版本: 0.1.0",
                "设备型号: Pixel-Test",
                "系统版本: Android 15 (API 35)",
            ),
        )

        val exported = requireNotNull(exporter.export())
        val text = exported.readText(Charsets.UTF_8)

        assertTrue(text.contains("===== 系统信息 ====="))
        assertTrue(text.contains("应用版本: 0.1.0"))
        assertTrue(text.contains("设备型号: Pixel-Test"))
        assertTrue(text.contains("系统版本: Android 15 (API 35)"))
        assertTrue(text.contains("应用启动"))
        assertTrue(text.indexOf("应用版本") < text.indexOf("应用启动"))
    }

    @Test
    fun `没有日志时即使有系统信息也不生成导出文件`() {
        val logDirectory = temporaryFolder.newFolder("no-logs-system-info")
        val outputDirectory = temporaryFolder.newFolder("no-logs-system-info-output")
        val exporter = DiagnosticsExporter(
            logDirectory = logDirectory,
            outputDirectory = outputDirectory,
            systemInfo = listOf("应用版本: 0.1.0"),
        )

        assertNull(exporter.export())
    }

    @Test
    fun `只有空白日志时不生成导出文件`() {
        val logDirectory = temporaryFolder.newFolder("blank-logs")
        val outputDirectory = temporaryFolder.newFolder("blank-logs-output")
        File(logDirectory, "diagnostics.log").writeText("  \n\t\n", Charsets.UTF_8)

        assertNull(DiagnosticsExporter(logDirectory, outputDirectory).export())
    }

    @Test
    fun `导出只读取当前这一个日志文件`() {
        val logDirectory = temporaryFolder.newFolder("ordered")
        val outputDirectory = temporaryFolder.newFolder("ordered-output")
        File(logDirectory, "diagnostics.1.log").writeText("old\n", Charsets.UTF_8)
        File(logDirectory, SessionFileSink.CURRENT_FILE_NAME).writeText("new\n", Charsets.UTF_8)

        val exported = requireNotNull(DiagnosticsExporter(logDirectory, outputDirectory).export())
        val text = exported.readText(Charsets.UTF_8)

        assertTrue(text.contains("new"))
        assertFalse(text.contains("old"))
        assertTrue(text.contains("===== diagnostics.log ====="))
    }

    @Test
    fun `导出补正半行换行`() {
        val logDirectory = temporaryFolder.newFolder("half-line")
        val outputDirectory = temporaryFolder.newFolder("half-line-output")
        File(logDirectory, SessionFileSink.CURRENT_FILE_NAME).writeText("half", Charsets.UTF_8)

        val exported = requireNotNull(DiagnosticsExporter(logDirectory, outputDirectory).export())
        val text = exported.readText(Charsets.UTF_8)

        assertTrue(text.contains("===== diagnostics.log =====\nhalf"))
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
    fun `每次启动追加一条会话标记并续写`() {
        val directory = temporaryFolder.newFolder("sessions")

        SessionFileSink(directory).write("第一次会话")
        SessionFileSink(directory).write("第二次会话")

        val text = File(directory, SessionFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)
        assertEquals(2, text.split(SessionFileSink.SESSION_MARKER_PREFIX).size - 1)
        assertTrue(text.contains("第一次会话"))
        assertTrue(text.contains("第二次会话"))
    }

    @Test
    fun `第三次启动截掉第一次会话`() {
        val directory = temporaryFolder.newFolder("third-session")

        SessionFileSink(directory).write("第一次会话")
        SessionFileSink(directory).write("第二次会话")
        SessionFileSink(directory).write("第三次会话")

        val text = File(directory, SessionFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)
        assertFalse(text.contains("第一次会话"))
        assertTrue(text.contains("第二次会话"))
        assertTrue(text.contains("第三次会话"))
        assertEquals(2, text.split(SessionFileSink.SESSION_MARKER_PREFIX).size - 1)
    }

    @Test
    fun `多字节内容跨会话截断不破字符`() {
        val directory = temporaryFolder.newFolder("utf8-session")

        SessionFileSink(directory).write("中文会话一".repeat(20))
        SessionFileSink(directory).write("中文会话二")
        SessionFileSink(directory).write("中文会话三")

        val text = File(directory, SessionFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)
        assertFalse(text.contains("中文会话一"))
        assertTrue(text.contains("中文会话二"))
        assertTrue(text.contains("中文会话三"))
        assertFalse(text.contains('�'))
    }

    @Test
    fun `单文件超过上限时丢掉最旧会话`() {
        val directory = temporaryFolder.newFolder("session-cap")
        SessionFileSink(directory, maxFileBytes = 256).write("旧会话" + "x".repeat(200))
        SessionFileSink(directory, maxFileBytes = 256).write("新会话")

        val text = File(directory, SessionFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)

        assertFalse(text.contains("旧会话"))
        assertTrue(text.contains("新会话"))
        // 始终只有一个文件，不再产生轮转文件。
        assertEquals(1, directory.listFiles().orEmpty().count { it.isFile })
    }

    @Test
    fun `单会话写超上限后不超过上限且最新日志仍在`() {
        val directory = temporaryFolder.newFolder("session-tail")
        val fileSink = SessionFileSink(directory, maxFileBytes = 512)

        repeat(40) { index -> fileSink.write("line-$index-" + "x".repeat(30)) }

        val current = File(directory, SessionFileSink.CURRENT_FILE_NAME)
        val text = current.readText(Charsets.UTF_8)

        assertTrue(current.length() <= 512)
        assertTrue(text.startsWith(SessionFileSink.SESSION_MARKER_PREFIX))
        assertTrue(text.contains("line-39-"))
        assertFalse(text.contains("line-0-"))
    }

    @Test
    fun `极小上限下连续写入不留半行`() {
        val directory = temporaryFolder.newFolder("session-lines")
        val fileSink = SessionFileSink(directory, maxFileBytes = 200)

        repeat(20) { index -> fileSink.write("line-$index") }

        val text = File(directory, SessionFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)
        assertTrue(text.endsWith("\n"))
        val lines = text.trimEnd('\n').split('\n')
        assertTrue(lines.first().startsWith(SessionFileSink.SESSION_MARKER_PREFIX))
        // 每一行都必须是完整行：半行或与下一行粘连都会在这里露出来。
        lines.drop(1).forEach { line ->
            val parts = line.split(' ')
            assertEquals(line, 3, parts.size)
            assertTrue(line, parts[0].matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertTrue(line, parts[1].matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")))
            assertTrue(line, parts[2].matches(Regex("line-\\d+")))
        }
    }

    @Test
    fun `放不下一整行时丢弃该行而不是写半行`() {
        val directory = temporaryFolder.newFolder("session-drop")
        val fileSink = SessionFileSink(directory, maxFileBytes = 64)

        fileSink.write("消息" + "x".repeat(200))

        val text = File(directory, SessionFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)
        assertTrue(text.endsWith("\n"))
        // 只剩会话标记这一行，没有半行残留。
        assertEquals(1, text.trimEnd('\n').split('\n').size)
        assertFalse(text.contains("消息"))
    }

    @Test
    fun `升级前没有会话标记的旧文件按一次会话计`() {
        val directory = temporaryFolder.newFolder("legacy")
        val current = File(directory, SessionFileSink.CURRENT_FILE_NAME)
        current.writeText("旧日志\n".repeat(40), Charsets.UTF_8)

        SessionFileSink(directory, maxFileBytes = 200).write("新日志")

        val text = current.readText(Charsets.UTF_8)
        assertFalse(text.contains("旧日志"))
        assertTrue(text.contains("新日志"))
    }

    @Test
    fun `正文里的会话标记字样不当作会话边界`() {
        val directory = temporaryFolder.newFolder("marker-in-body")
        SessionFileSink(directory, maxFileBytes = 4096).write("片段: =====" + " 会话开始 不是边界")
        SessionFileSink(directory, maxFileBytes = 4096).write("第二次会话")

        val text = File(directory, SessionFileSink.CURRENT_FILE_NAME).readText(Charsets.UTF_8)
        assertTrue(text.contains("片段: ====="))
        assertTrue(text.contains("第二次会话"))
    }

    @Test
    fun `有内容时不清理旧导出`() {
        val logDirectory = temporaryFolder.newFolder("keep-old-logs")
        val outputDirectory = temporaryFolder.newFolder("keep-old-output")
        val oldExport = File(outputDirectory, "cc-player-diagnostics-old.txt").apply { writeText("旧") }
        File(logDirectory, SessionFileSink.CURRENT_FILE_NAME).writeText("new\n", Charsets.UTF_8)

        assertTrue(DiagnosticsExporter(logDirectory, outputDirectory).export() != null)
        assertTrue(oldExport.exists())
    }

    @Test
    fun `并发写入不丢行`() {
        val directory = temporaryFolder.newFolder("concurrent")
        val fileSink = SessionFileSink(directory, maxFileBytes = 1_000_000)

        val threads = (0 until 4).map { thread ->
            Thread {
                repeat(50) { index -> fileSink.write("t$thread-$index") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // 一行会话标记加 200 行日志。
        val current = File(directory, SessionFileSink.CURRENT_FILE_NAME)
        assertEquals(201, current.readLines().size)
    }
}
