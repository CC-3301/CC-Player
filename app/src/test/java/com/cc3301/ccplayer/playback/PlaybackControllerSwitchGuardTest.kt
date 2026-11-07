package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
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
 * 切集看护：读失败重发一次、10 秒无进展判失败，以及唯一的累计运行时长计时。
 * 切集期间只有这一套计时，退后台与 Surface 分离都不挂起它：
 * 后台正常播放靠「播放位置前进」算进展，没有进展一样到 10 秒判失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSwitchGuardTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `a switch without progress in the background fails at ten seconds`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(1_000)
        controller.setAppInForeground(false)
        engine.reportFileLoaded()
        // 后台不挂起计时：加载完成不算进展，到 10 秒照样判失败并回退原片。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1_000 - 1)
        runCurrent()
        assertTrue(controller.switching.value)
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(0, controller.currentIndex.value)
        assertTrue(controller.restoring.value)
    }

    @Test
    fun `a switch that advances position in the background is not failed`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        controller.setAppInForeground(false)
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        engine.reportPositionChanged(1_000, 600_000)

        // 后台位置在前进就是进展：退后台 30 秒不按后台时间判失败。
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(failures.isEmpty())
        assertFalse(controller.switching.value)
    }

    @Test
    fun `a switch without progress keeps the guard running while the surface is detached`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(1_000)
        controller.setRenderSurfaceAvailable(false)
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1_000)
        runCurrent()

        // Surface 分离同样不挂起计时：无进展到 10 秒照样判失败。
        assertEquals(1, failures.size)
    }

    @Test
    fun `a switch is judged by one guard only`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()

        // 没有进展也不按时间重发：到 9 秒仍在等首帧，一次都不重发。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1)
        runCurrent()
        assertTrue(controller.switching.value)
        assertEquals(0, engine.discardInFlightCalls)
        assertEquals(1, engine.startedUrls.count { it == "root/ep2.mkv" })

        // 10 秒判失败，回退原片一次。
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(1, engine.startedUrls.count { it == "root/ep2.mkv" })

        // 再等 10 秒：切集期间不参与的无帧窗口没有第二次判失败，失败的目标也不再重发。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertEquals(1, engine.startedUrls.count { it == "root/ep2.mkv" })

        // 回退原片自己也是同一条看护：加载没回来就到点判失败，没有第三层可退；
        // 同一次切集只提示一次「播放失败」，恢复再失败不再重复提示。
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `a read failure during a switch resends the same target once`() = runTest {
        var discards = 0
        val controller = controller(
            discard = {
                discards++
                true
            },
        )
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        assertEquals(1, engine.startedUrls.count { it == "root/ep2.mkv" })

        // 播放核心因读失败结束本轮：丢弃字节源后以同一目标重发一次，不换目标索引。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(1, discards)
        assertEquals(1, engine.discardInFlightCalls)
        assertEquals(2, engine.startedUrls.count { it == "root/ep2.mkv" })
        assertEquals(1, controller.currentIndex.value)
        assertTrue(controller.switching.value)
        assertTrue(failures.isEmpty())

        // 重发这一轮也读失败：直接按切集失败收尾，不再第三次加载。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(1, discards)
        assertEquals(2, engine.startedUrls.count { it == "root/ep2.mkv" })
        assertEquals(1, failures.size)
        assertEquals(0, controller.currentIndex.value)
        assertTrue(controller.restoring.value)
    }

    @Test
    fun `读到失败时已有进展则直接失败收尾而不是停在加载`() = runTest {
        val diagnostics = RecordingDiagnostics()
        val controller = controller(diagnostics = diagnostics)
        val failures = collectFailures(controller)

        open(controller, "ep1.mkv")
        engine.reportFileLoaded()
        // 位置前进已经记成进展：看护随之撤销，之后不能再靠重发拖住。
        engine.reportPositionChanged(1_000, 600_000)
        runCurrent()

        engine.reportPlaybackFailed("读取超时")
        runCurrent()

        // 不能重发时按播放核心报错收尾：既不重发第二次加载，也不能停在加载中。
        assertEquals(1, engine.startedUrls.size)
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
        val text = diagnostics.messages.joinToString("\n")
        assertTrue(text.contains("失败来源: 播放核心报错（读取超时）"))
        assertFalse(text.contains("失败来源: 无进展超时"))
    }

    @Test
    fun `重发准备期间的重复失败回调不会提前判失败`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var discards = 0
        val controller = controller(
            discard = {
                discards++
                gate.await()
                true
            },
        )
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(1, discards)

        // 同一次失败又回一条：重发准备中，忽略，不判失败也不重复丢弃。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(1, discards)
        assertTrue(failures.isEmpty())
        assertTrue(controller.switching.value)

        gate.complete(Unit)
        runCurrent()
        assertEquals(2, engine.startedUrls.count { it == "root/ep2.mkv" })
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `旧重发准备的协程不会抹掉新目标的在途标记`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var discards = 0
        val controller = controller(
            discard = {
                discards++
                when (discards) {
                    1 -> firstGate.await()
                    2 -> secondGate.await()
                }
                true
            },
        )
        open(controller, "ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        // 丢弃还卡着时目标前移，新目标再失败也开始自己的重发准备。
        controller.next()
        runCurrent()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(2, discards)

        // 旧目标的重发准备这时才返回：不能抹掉新目标刚置的在途标记。
        firstGate.complete(Unit)
        runCurrent()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        // 新目标仍在重发准备中：这次回调被忽略，不另起一次重发。
        assertEquals(2, discards)

        secondGate.complete(Unit)
        runCurrent()
        assertEquals(2, engine.startedUrls.count { it == "root/ep3.mkv" })

        // 新目标的重发也失败：直接判失败，不再多一次加载。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(2, engine.startedUrls.count { it == "root/ep3.mkv" })
        assertEquals(1, failures.size)
    }

    @Test
    fun `重发被目标前移作废时不记重发次数与日志`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val diagnostics = RecordingDiagnostics()
        val controller = controller(
            discard = {
                gate.await()
                true
            },
            diagnostics = diagnostics,
        )
        open(controller, "ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        // 丢弃还卡着时目标前移：这一轮重发作废。
        controller.next()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        // 作废的重发不会多加载一次，也不影响新目标。
        assertEquals(1, engine.startedUrls.count { it == "root/ep2.mkv" })
        assertEquals(2, controller.currentIndex.value)

        // 10 秒仍无进展：失败行里重发次数为 0，且没有写重发行。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        val text = diagnostics.messages.joinToString("\n")
        assertFalse(text.contains("加载重发"))
        assertTrue(text.contains("重发: 0 次"))
        assertEquals(1, failures.size)
    }

    @Test
    fun `a switch without progress fails at ten seconds`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1)
        runCurrent()
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        // 回退原片：回到原位置并保持暂停，提示一次。
        assertEquals(1, failures.size)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(40_000L, engine.startedPositions.last())
    }

    @Test
    fun `a position advance ends the switch before any retry`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        engine.reportPositionChanged(1_000, 600_000)

        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS * 2)
        runCurrent()
        assertTrue(failures.isEmpty())
        assertEquals(0, engine.discardInFlightCalls)
    }

    @Test
    fun `a failed switch restores the original and keeps the session paused`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(90_000, 600_000)
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()

        // 回退原片：重新打开原位置、保持暂停、只提示一次。
        assertEquals(1, failures.size)
        assertTrue(controller.restoring.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(90_000L, engine.startedPositions.last())

        engine.reportMediaLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.restoring.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(1, failures.size)
    }

    @Test
    fun `a restore read failure resends once and a second failure ends the session`() = runTest {
        var discards = 0
        val controller = controller(
            discard = {
                discards++
                true
            },
        )
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        // 先让切集失败，进入恢复原片。
        controller.next()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertTrue(controller.restoring.value)
        val reported = failures.size
        val restoreStarts = engine.startedUrls.count { it == "root/ep1.mkv" }
        val discardsBefore = discards

        // 恢复原片的读失败：以原位置重发一次。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(restoreStarts + 1, engine.startedUrls.count { it == "root/ep1.mkv" })
        assertEquals(discardsBefore + 1, discards)
        assertEquals(40_000L, engine.startedPositions.last())
        assertEquals(reported, failures.size)

        // 重发也失败：直接落到既有失败收尾，没有第三层可退。
        // 同一次切集只提示一次「播放失败」，恢复再失败不再重复提示。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(reported, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `a restore without progress in the background fails at ten seconds`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertTrue(controller.restoring.value)
        val reported = failures.size

        // 恢复原片同样不因后台挂起：无进展到 10 秒直接结束会话。
        controller.setAppInForeground(false)
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        // 恢复失败不重复提示，直接结束会话。
        assertEquals(reported, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `starting a new switch during a restore restarts the guard`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertTrue(controller.restoring.value)
        val reportedByFirstFailure = failures.size

        // 恢复原片走到 4 秒时再切一次：新加载重新起算重发预算与 10 秒判失败。
        advanceTimeBy(4_000)
        runCurrent()
        controller.next()
        runCurrent()
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertTrue(controller.switching.value)

        // 恢复原片那条计时已经撤掉：它不再触发重发，也不再判失败。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1)
        runCurrent()
        assertEquals(reportedByFirstFailure, failures.size)
        assertTrue(controller.switching.value)
        assertEquals(1, controller.currentIndex.value)
    }

    @Test
    fun `retargeting after a retry gives the new target a fresh retry`() = runTest {
        var discards = 0
        val controller = controller(
            discard = {
                discards++
                true
            },
        )
        open(controller, "ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(1, discards)

        // 重发后再前移目标：计时与重发预算都以新目标重新起算，新目标也获得一次重发。
        advanceTimeBy(1_000)
        controller.next()
        runCurrent()
        assertEquals(2, controller.currentIndex.value)

        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(2, discards)
        assertTrue(failures.isEmpty())

        // 新目标第二次读失败才判失败，重发上限仍是一次。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(2, discards)
        assertEquals(1, failures.size)
    }

    @Test
    fun `an initial load uses the same guard as a switch`() = runTest {
        var discards = 0
        val controller = controller(
            discard = {
                discards++
                true
            },
        )
        val failures = collectFailures(controller)
        open(controller, "ep1.mkv")
        assertEquals(1, engine.startedUrls.size)

        // 时间本身不触发重发：到 4 秒仍只有第一次加载。
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, engine.startedUrls.size)

        // 读到失败：丢掉字节源后以同一目标重发一次。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(1, discards)
        assertEquals(2, engine.startedUrls.size)
        assertEquals(PlaybackState.Loading, controller.state.value)

        // 重发也失败：按首次打开的失败收尾，不在加载中干等。
        engine.reportPlaybackFailed("读取超时")
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(2, engine.startedUrls.size)
    }

    @Test
    fun `an initial load in the background fails at ten seconds without progress`() = runTest {
        val controller = controller()
        val failures = collectFailures(controller)
        open(controller, "ep1.mkv")

        advanceTimeBy(1_000)
        controller.setAppInForeground(false)
        // 后台不挂起计时：无进展到 10 秒判失败，不再无限转圈。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1_000 - 1)
        runCurrent()
        assertTrue(failures.isEmpty())
        assertEquals(PlaybackState.Loading, controller.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `切集期间重挂不请求精确刷新且看护照旧`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(1_000)
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
        runCurrent()

        // 切集期间重挂不插精确刷新，也不重置看护计时。
        assertEquals(0, engine.refreshFrameCalls)
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1_000 - 1)
        runCurrent()
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures.size)
    }

    private fun TestScope.controller(
        discard: suspend () -> Boolean = { true },
        diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    ): PlaybackController = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        nowMillis = { testScheduler.currentTime },
        diagnostics = diagnostics,
        discardByteSources = discard,
    )

    /** 记下诊断日志的出口：放行与拦截都按日志内容断言。 */
    private class RecordingDiagnostics : DiagnosticsLog {
        override val enabled: Boolean = true

        val messages = CopyOnWriteArrayList<String>()

        override fun log(message: String) {
            messages += message
        }
    }

    /** 起播第一集并等首帧就绪，作为切集前的正常播放状态。 */
    private fun TestScope.playingController(
        vararg names: String = arrayOf("ep1.mkv", "ep2.mkv"),
    ): PlaybackController {
        val controller = controller()
        open(controller, *names)
        engine.reportMediaLoaded()
        return controller
    }

    private fun TestScope.collectFailures(controller: PlaybackController): List<PlaybackFailure> {
        val failures = mutableListOf<PlaybackFailure>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackFailures.collect { failures += it }
        }
        return failures
    }

    private fun open(
        controller: PlaybackController,
        vararg names: String,
        current: String = names.first(),
    ) {
        val files = names.map { MediaFile(name = it, location = "root/$it") }
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION, files, files.first { it.name == current })
    }
}
