package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输恢复：本地传输失效按真实目标与位置换传输恢复，不占普通重发。
 * 同一目标连续最多两次换传输，真实进展清零，每次换传输重置一次完整 10 秒看护；
 * 新传输仍连不上按网络／服务器终态收尾，仍是本地 SMB 异常进入可恢复暂停／错误。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTransportRecoveryTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    /** 由用例设定的失败分类；为空表示不是传输失败。 */
    private var classification: PlaybackTransportFailure? = null

    private val target = "smb://nas/media/"

    @Test
    fun `播放中本地传输失效按原位置换传输续播而不结束会话`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val controller = controller()
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)
        val starts = engine.startedUrls.size

        engine.reportPlaybackFailed("读取超时")
        runCurrent()

        // 同一个目标、同一个位置：允许短暂缓冲，不提示失败也不结束会话。
        assertEquals(starts + 1, engine.startedUrls.size)
        assertEquals(target + "ep1.mkv", engine.startedUrls.last())
        assertEquals(40_000L, engine.startedPositions.last())
        assertEquals(0, controller.currentIndex.value)
        assertTrue(failures.isEmpty())
        assertFalse(controller.playbackFailed.value)

        // 新传输上的目标帧回来：接着播。
        engine.reportMediaLoaded()
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertFalse(controller.restoring.value)
    }

    @Test
    fun `后台播放中传输失效立即换传输不等回前台`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val controller = controller()
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)
        controller.setAppInForeground(false)
        val starts = engine.startedUrls.size

        engine.reportPlaybackFailed("读取超时")
        runCurrent()

        // 后台不做任何等待：当场换传输并按原位置重载。
        assertEquals(starts + 1, engine.startedUrls.size)
        assertEquals(40_000L, engine.startedPositions.last())
    }

    @Test
    fun `未绑定界面时也按真实分类恢复传输`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val controller = controller()
        // 刻意不调用 attachNetworkSources：分类出口在构造时给足，不随界面绑定变化。
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)
        val starts = engine.startedUrls.size

        engine.reportPlaybackFailed("读取超时")
        runCurrent()

        assertEquals(starts + 1, engine.startedUrls.size)
        assertEquals(40_000L, engine.startedPositions.last())
        assertFalse(controller.playbackFailed.value)
    }

    @Test
    fun `新传输结果在真实恢复之后才写进诊断`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val diagnostics = RecordingDiagnostics()
        val controller = controller(diagnostics)
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)

        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        // 这一步只是发起了换传输：不把意图当结果写。
        assertFalse(diagnostics.messages.joinToString("\n").contains("已换传输"))

        engine.reportMediaLoaded()
        engine.reportPositionChanged(60_000, 600_000)
        classification = null
        engine.reportPlaybackFailed("解码失败")
        runCurrent()

        assertTrue(diagnostics.messages.joinToString("\n").contains("新传输结果: 新传输已恢复"))
    }

    @Test
    fun `首次打开遇到传输失效按同一目标换传输恢复`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val controller = controller()
        open(controller, "ep1.mkv")
        val failures = collectFailures(controller)
        val starts = engine.startedUrls.size

        engine.reportPlaybackFailed("读取超时")
        runCurrent()

        assertEquals(starts + 1, engine.startedUrls.size)
        assertEquals(target + "ep1.mkv", engine.startedUrls.last())
        assertTrue(failures.isEmpty())
        assertFalse(controller.playbackFailed.value)
    }

    @Test
    fun `恢复耗尽后按播放键按当前目标与位置重新加载`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val controller = controller()
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)

        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(PlaybackState.Paused, controller.state.value)
        val starts = engine.startedUrls.size

        controller.togglePlayPause()
        runCurrent()

        // 用户主动重试：重新加载当前目标，位置不退回首帧，预算重新给满。
        assertEquals(starts + 1, engine.startedUrls.size)
        assertEquals(target + "ep1.mkv", engine.startedUrls.last())
        assertFalse(controller.playbackFailed.value)
    }

    @Test
    fun `自动切集途中传输失效恢复当时的真实目标`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        assertEquals(1, engine.startedUrls.count { it == target + "ep2.mkv" })

        engine.reportPlaybackFailed("读取超时")
        runCurrent()

        // 不在恢复前回退原片：故障目标自己再加载一次。
        assertEquals(2, engine.startedUrls.count { it == target + "ep2.mkv" })
        assertEquals(1, controller.currentIndex.value)
        assertTrue(controller.switching.value)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `同一目标连续最多换传输两次`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val diagnostics = RecordingDiagnostics()
        val controller = controller(diagnostics)
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)
        val starts = engine.startedUrls.size

        // 连续三次失效、中间没有真实进展：只换两次传输，第三次进入可恢复暂停／错误。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()

        assertEquals(starts + 2, engine.startedUrls.size)
        assertTrue(failures.isEmpty())
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(controller.playbackFailed.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(40_000L, controller.progress.value.positionMillis)

        val text = diagnostics.messages.joinToString("\n")
        assertTrue(text, text.contains("连续换传输: 2 次"))
        assertTrue(text, text.contains("新传输结果: 仍是本地 SMB 异常"))
    }

    @Test
    fun `真实进展把连续换传输次数清零`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val controller = controller()
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        // 新传输上真的出帧并前进：连续计数清零，下一次又能换满两次。
        engine.reportMediaLoaded()
        engine.reportPositionChanged(60_000, 600_000)

        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        engine.reportMediaLoaded()
        engine.reportPositionChanged(80_000, 600_000)

        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        engine.reportMediaLoaded()

        // 三次失效都被换传输接住：没有一次落到失败收尾。
        assertEquals(4, engine.startedUrls.size)
        assertTrue(failures.isEmpty())
        assertFalse(controller.playbackFailed.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `每次换传输重置一次完整十秒看护`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        advanceTimeBy(9_000)
        runCurrent()

        // 第 9 秒传输失效：换传输后重新给满 10 秒，不在原计时上判失败。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(2, engine.startedUrls.count { it == target + "ep2.mkv" })

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1)
        runCurrent()
        assertTrue(failures.isEmpty())
        assertEquals(2, engine.startedUrls.count { it == target + "ep2.mkv" })

        // 新的一次看护到点：还能再换一次（第 2 次），仍然不是失败收尾。
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, engine.startedUrls.count { it == target + "ep2.mkv" })
        assertTrue(failures.isEmpty())

        // 预算用满后再到 10 秒：按本地异常进入可恢复暂停／错误，不结束会话。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(controller.playbackFailed.value)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `新传输仍连不上时按网络服务器终态收尾`() = runTest {
        classification = PlaybackTransportFailure.Unreachable
        val diagnostics = RecordingDiagnostics()
        val controller = controller(diagnostics)
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)
        val starts = engine.startedUrls.size

        engine.reportPlaybackFailed("连不上服务器")
        runCurrent()

        // 不再换传输：走既有失败收尾。
        assertEquals(starts, engine.startedUrls.size)
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
        assertTrue(diagnostics.messages.joinToString("\n").contains("新传输结果: 网络／服务器终态"))
    }

    @Test
    fun `没有传输分类时沿用原有重发一次`() = runTest {
        classification = null
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()
        controller.next()
        engine.reportFileLoaded()

        engine.reportPlaybackFailed("解码失败")
        runCurrent()

        // 非传输失败：同一目标普通重发一次，仍按切集处理。
        assertEquals(2, engine.startedUrls.count { it == target + "ep2.mkv" })
        assertTrue(controller.switching.value)
    }

    @Test
    fun `诊断区分传输分类次数与真实目标`() = runTest {
        classification = PlaybackTransportFailure.Transport
        val diagnostics = RecordingDiagnostics()
        val controller = controller(diagnostics)
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()

        controller.next()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()

        val text = diagnostics.messages.joinToString("\n")
        assertTrue(text, text.contains("传输恢复: ep2.mkv"))
        assertTrue(text, text.contains("分类: 本地 SMB 传输失效"))
        assertTrue(text, text.contains("第 1 次"))
        assertTrue(text, text.contains("真实目标: " + target + "ep2.mkv"))
    }

    private fun TestScope.controller(
        diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    ): PlaybackController = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        nowMillis = { testScheduler.currentTime },
        diagnostics = diagnostics,
        discardByteSources = { true },
        classifyPlaybackFailure = { classification },
    )

    private fun open(controller: PlaybackController, vararg names: String) {
        val files = names.map { MediaFile(name = it, location = target + it) }
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION, files, files.first())
    }

    private fun TestScope.collectFailures(controller: PlaybackController): List<PlaybackFailure> {
        val failures = mutableListOf<PlaybackFailure>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackFailures.collect { failures += it }
        }
        return failures
    }

    /** 记下诊断日志的出口：断言分类、次数与真实目标都写进日志。 */
    private class RecordingDiagnostics : DiagnosticsLog {
        override val enabled: Boolean = true

        val messages = CopyOnWriteArrayList<String>()

        override fun log(message: String) {
            messages += message
        }
    }
}
