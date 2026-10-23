package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.MemoryDiagnosticsSink
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerDiagnosticsTest {

    private val engine = FakePlaybackEngine()

    @Test
    fun `开启后记录加载与播放状态迁移`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher()).apply { enabled = true }
        val media = MediaFile(name = "ep1.mkv", location = "smb://nas/share/ep1.mkv")
        val controller = PlaybackController(
            engine,
            FakePlaybackProgressRepository(),
            CoroutineScope(UnconfinedTestDispatcher()),
            diagnostics = diagnostics,
        )

        controller.openPlaylist(listOf(media), media)
        engine.reportMediaLoaded()

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("加载开始: ep1.mkv"))
        assertTrue(text.contains("加载成功: ep1.mkv"))
        assertTrue(text.contains("播放状态: Idle -> Loading"))
        assertTrue(text.contains("播放状态: Loading -> Playing"))
    }

    @Test
    fun `看门狗触发时写日志`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = controller(diagnostics)
        open(controller)

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()

        assertTrue(sink.lines.any { it.contains("加载看门狗触发: ep1.mkv") })
    }

    @Test
    fun `看门狗日志在文件名为空时回退索引`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = controller(diagnostics)
        controller.openPlaylist(
            listOf(MediaFile(name = "", location = "root/ep0.mkv")),
            MediaFile(name = "", location = "root/ep0.mkv"),
        )

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()

        assertTrue(sink.lines.any { it.contains("加载看门狗触发: 索引 0") })
    }

    @Test
    fun `关闭时不写看门狗日志`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = false)
        val controller = controller(diagnostics)
        open(controller)

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()

        assertTrue(sink.lines.isEmpty())
    }

    @Test
    fun `Surface 重挂与刷新结果写日志`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = playingController(diagnostics)

        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS)
        runCurrent()

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("Surface 分离"))
        assertTrue(text.contains("Surface 重挂: 等待新帧"))
        assertTrue(text.contains("Surface 恢复: 已触发非 seek 刷新"))
    }

    @Test
    fun `刷新未执行时记录结果`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = playingController(diagnostics)
        engine.refreshSurfaceResult = false

        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS)
        runCurrent()

        assertTrue(sink.lines.any { it.contains("Surface 恢复: 刷新未执行") })
    }

    @Test
    fun `没有活动会话时重挂不写等待新帧`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = controller(diagnostics)

        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS)
        runCurrent()

        assertFalse(sink.lines.any { it.contains("Surface 重挂: 等待新帧") })
    }

    @Test
    fun `Surface 重挂后收到新帧记录恢复结果`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = playingController(diagnostics)

        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
        engine.reportFramePresented()
        runCurrent()

        assertTrue(sink.lines.any { it.contains("Surface 恢复: 新帧已上屏") })
    }

    @Test
    fun `关闭时不写 Surface 日志`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = false)
        val controller = playingController(diagnostics)

        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS)
        runCurrent()

        assertTrue(sink.lines.isEmpty())
    }

    @Test
    fun `切集失败记录播放核心错误文本、目标、令牌与阶段`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = controller(diagnostics)
        val first = MediaFile(name = "ep1.mkv", location = "root/ep1.mkv")
        val second = MediaFile(name = "ep2.mkv", location = "root/ep2.mkv")
        controller.openPlaylist(listOf(first, second), first)
        engine.reportMediaLoaded()

        controller.next()
        val token = engine.currentLoadToken
        engine.reportPlaybackFailed("无法打开文件")

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("切集失败: ep2.mkv"))
        assertTrue(text.contains("目标: root/ep2.mkv"))
        assertTrue(text.contains("加载令牌: $token"))
        assertTrue(text.contains("阶段: 切集"))
        assertTrue(text.contains("来源: 播放核心报错（无法打开文件）"))
    }

    @Test
    fun `加载失败记录播放核心错误文本`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher()).apply { enabled = true }
        val controller = PlaybackController(
            engine,
            FakePlaybackProgressRepository(),
            CoroutineScope(UnconfinedTestDispatcher()),
            diagnostics = diagnostics,
        )
        open(controller)

        engine.reportPlaybackFailed("解码失败")

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("加载失败: ep1.mkv"))
        assertTrue(text.contains("阶段: 首次加载"))
        assertTrue(text.contains("来源: 播放核心报错（解码失败）"))
    }

    @Test
    fun `看门狗失败与播放核心报错可区分`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = controller(diagnostics)
        open(controller)

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("加载看门狗触发: ep1.mkv"))
        assertTrue(text.contains("目标: root/ep1.mkv"))
        assertTrue(text.contains("阶段: 首次加载"))
        assertTrue(text.contains("来源: 看门狗超时"))
    }

    @Test
    fun `错误文本为空时记录无错误文本`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = controller(diagnostics)
        val first = MediaFile(name = "ep1.mkv", location = "root/ep1.mkv")
        val second = MediaFile(name = "ep2.mkv", location = "root/ep2.mkv")
        controller.openPlaylist(listOf(first, second), first)
        engine.reportMediaLoaded()

        controller.next()
        engine.reportPlaybackFailed("   ")

        assertTrue(sink.lines.any { it.contains("来源: 播放核心报错（无错误文本）") })
    }

    @Test
    fun `播放中失败记录阶段与错误文本`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = playingController(diagnostics)

        engine.reportPlaybackFailed("渲染失败")

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("播放失败: ep1.mkv"))
        assertTrue(text.contains("目标: root/ep1.mkv"))
        assertTrue(text.contains("阶段: 播放"))
        assertTrue(text.contains("来源: 播放核心报错（渲染失败）"))
    }

    @Test
    fun `重复失败只记录一次`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = playingController(diagnostics)

        engine.reportPlaybackFailed("第一次")
        engine.reportPlaybackFailed("第二次")

        assertEquals(1, sink.lines.count { it.contains("播放失败:") })
    }

    @Test
    fun `切集无帧超时记录目标、令牌与来源`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = diagnostics(sink, enabled = true)
        val controller = controller(diagnostics)
        val first = MediaFile(name = "ep1.mkv", location = "root/ep1.mkv")
        val second = MediaFile(name = "ep2.mkv", location = "root/ep2.mkv")
        controller.openPlaylist(listOf(first, second), first)
        engine.reportMediaLoaded()

        controller.next()
        val token = engine.currentLoadToken
        engine.reportFileLoaded()
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS)
        runCurrent()

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("切集失败: ep2.mkv"))
        assertTrue(text.contains("目标: root/ep2.mkv"))
        assertTrue(text.contains("加载令牌: $token"))
        assertTrue(text.contains("阶段: 切集"))
        assertTrue(text.contains("来源: 无帧超时"))
    }

    private fun TestScope.diagnostics(sink: MemoryDiagnosticsSink, enabled: Boolean): Diagnostics =
        Diagnostics(sink, UnconfinedTestDispatcher(testScheduler)).apply { this.enabled = enabled }

    private fun TestScope.controller(diagnostics: Diagnostics): PlaybackController =
        PlaybackController(
            engine,
            FakePlaybackProgressRepository(),
            backgroundScope,
            diagnostics = diagnostics,
            nowMillis = { testScheduler.currentTime },
        )

    private fun open(controller: PlaybackController) {
        val media = MediaFile(name = "ep1.mkv", location = "root/ep1.mkv")
        controller.openPlaylist(listOf(media), media)
    }

    private fun TestScope.playingController(diagnostics: Diagnostics): PlaybackController {
        val controller = controller(diagnostics)
        open(controller)
        engine.reportMediaLoaded()
        return controller
    }
}
