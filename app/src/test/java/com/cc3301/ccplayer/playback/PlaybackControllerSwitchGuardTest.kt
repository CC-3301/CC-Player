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
 * 切集看护：读失败重发一次、10 秒无进展判失败，以及唯一的累计运行时长计时。
 * 切集期间只有这一套计时，后台与 Surface 分离的时间不计入任何判据。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSwitchGuardTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `a switch that loads in the background is not failed on return`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(1_000)
        controller.setAppInForeground(false)
        engine.reportFileLoaded()
        // 后台待的时间远超 10 秒：这一段不进切集计时。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS * 6)
        runCurrent()
        assertTrue(failures.isEmpty())

        controller.setAppInForeground(true)
        runCurrent()
        // 回前台不立刻判失败：只接着剩下的 9 秒走。
        assertTrue(controller.switching.value)
        assertTrue(failures.isEmpty())

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1_000 - 1)
        runCurrent()
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
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
    fun `a restore in the background is not failed on return`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertTrue(controller.restoring.value)
        val reported = failures.size

        // 恢复原片走到 1 秒时退后台：后台时间不进恢复的 10 秒判据。
        advanceTimeBy(1_000)
        controller.setAppInForeground(false)
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS * 6)
        runCurrent()
        assertEquals(reported, failures.size)
        assertTrue(controller.restoring.value)

        controller.setAppInForeground(true)
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1_000 - 1)
        runCurrent()
        assertEquals(reported, failures.size)

        advanceTimeBy(1)
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

        // 恢复原片那条计时已经撤掉：它的 5 秒点与 10 秒点都不再重试、不再判失败。
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
    fun `an initial load in the background is not failed on return`() = runTest {
        val controller = controller()
        val failures = collectFailures(controller)
        open(controller, "ep1.mkv")

        advanceTimeBy(1_000)
        controller.setAppInForeground(false)
        // 后台待的时间远超 10 秒：这一段不进加载计时。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS * 6)
        runCurrent()
        assertTrue(failures.isEmpty())
        assertEquals(PlaybackState.Loading, controller.state.value)

        controller.setAppInForeground(true)
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

    private fun TestScope.collectFailures(controller: PlaybackController): List<Unit> {
        val failures = mutableListOf<Unit>()
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
        controller.openPlaylist(files, files.first { it.name == current })
    }
}
