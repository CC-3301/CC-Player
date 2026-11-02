package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
 * 切集看护：预检、5 秒重试、10 秒判失败，以及唯一的累计运行时长计时。
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
        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS * 6)
        runCurrent()
        assertTrue(failures.isEmpty())

        controller.setAppInForeground(true)
        runCurrent()
        // 回前台不立刻判失败：只接着剩下的 9 秒走。
        assertTrue(controller.switching.value)
        assertTrue(failures.isEmpty())

        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS - 1_000 - 1)
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

        // 没有就绪兜底：到 4 秒仍在等首帧，也不重试。
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(controller.switching.value)
        assertEquals(0, engine.discardInFlightCalls)

        // 5 秒重试一次。
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, engine.discardInFlightCalls)

        // 10 秒判失败，回退原片一次。
        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS - 5_000)
        runCurrent()
        assertEquals(1, failures.size)
        val starts = engine.startedUrls.size
        val originStarts = engine.startedUrls.count { it == "root/ep1.mkv" }

        // 再等到 60 秒：切集期间不参与的无帧窗口不会带来第二次判失败或第二次回退。
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(starts, engine.startedUrls.size)
        assertEquals(originStarts, engine.startedUrls.count { it == "root/ep1.mkv" })
    }

    @Test
    fun `an unreadable target is not switched`() = runTest {
        val controller = controller(probe = { false })
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)
        val starts = engine.startedUrls.size

        controller.next()
        runCurrent()

        // 预检失败：不发切换、不动播放列表位置，当前视频继续播放并提示。
        assertEquals(1, failures.size)
        assertFalse(controller.switching.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(starts, engine.startedUrls.size)
        assertEquals(0, engine.pauseCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a switch without progress retries exactly once`() = runTest {
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

        advanceTimeBy(PlaybackController.SWITCH_RETRY_DELAY_MILLIS)
        runCurrent()
        // 重试：丢弃字节源后以同一目标重发一次加载，不换目标索引。
        assertEquals(1, discards)
        assertEquals(1, engine.discardInFlightCalls)
        assertEquals(2, engine.startedUrls.count { it == "root/ep2.mkv" })
        assertEquals(1, controller.currentIndex.value)
        assertTrue(failures.isEmpty())

        advanceTimeBy(
            PlaybackController.SWITCH_FAIL_DELAY_MILLIS - PlaybackController.SWITCH_RETRY_DELAY_MILLIS,
        )
        runCurrent()
        // 第二次到点直接判失败，不再重试。
        assertEquals(1, discards)
        assertEquals(2, engine.startedUrls.count { it == "root/ep2.mkv" })
        assertEquals(1, failures.size)
    }

    @Test
    fun `a switch without progress fails at ten seconds`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS - 1)
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

        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS * 2)
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
        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS)
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
    fun `starting a new switch during a restore cancels the restore watchdog`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS)
        runCurrent()
        assertTrue(controller.restoring.value)
        val reportedByFirstFailure = failures.size

        // 恢复期的 60 秒看门狗到点前 5 秒再切一次：新切集不能被它打成「看门狗超时」。
        advanceTimeBy(
            PlaybackController.LOAD_WATCHDOG_MILLIS - PlaybackController.SWITCH_FAIL_DELAY_MILLIS - 5_000,
        )
        runCurrent()
        controller.next()
        runCurrent()
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertTrue(controller.switching.value)

        advanceTimeBy(5_000)
        runCurrent()
        // 旧看门狗到点也不会再报一次失败；新切集只走到 5 秒重试。
        assertEquals(reportedByFirstFailure, failures.size)
        assertTrue(controller.switching.value)
        assertEquals(1, controller.currentIndex.value)
    }

    @Test
    fun `retargeting after a retry keeps the retry budget`() = runTest {
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
        advanceTimeBy(PlaybackController.SWITCH_RETRY_DELAY_MILLIS)
        runCurrent()
        assertEquals(1, discards)

        // 重试后再前移目标：计时重新起算，但重试预算已经用完。
        advanceTimeBy(1_000)
        controller.next()
        runCurrent()
        assertEquals(2, controller.currentIndex.value)

        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS)
        runCurrent()
        assertEquals(1, discards)
        assertEquals(1, failures.size)
    }

    @Test
    fun `an unreadable precheck times out without switching`() = runTest {
        val controller = controller(
            probe = {
                delay(60_000)
                true
            },
        )
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()
        val failures = collectFailures(controller)
        val starts = engine.startedUrls.size

        controller.next()
        runCurrent()
        advanceTimeBy(PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertTrue(failures.isEmpty())

        // 预算到点按不可读处理：不发起切集，当前播放继续。
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures.size)
        assertFalse(controller.switching.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(starts, engine.startedUrls.size)
    }

    @Test
    fun `clicking next twice during the precheck only switches to the last target`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val probed = mutableListOf<String>()
        val controller = controller(
            probe = {
                probed += it.name
                gate.await()
                true
            },
        )
        open(controller, "ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportMediaLoaded()

        controller.next()
        runCurrent()
        controller.next()
        runCurrent()
        // 两次点击都进过预检，第一次在途预检被第二次替换。
        assertEquals(listOf("ep2.mkv", "ep3.mkv"), probed)

        gate.complete(Unit)
        runCurrent()

        // 只认最后一次目标：直接从当前集切到第三集，中间集不加载。
        assertEquals(2, controller.currentIndex.value)
        assertEquals("root/ep3.mkv", engine.startedUrls.last())
        assertEquals(2, engine.startedUrls.size)
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

        advanceTimeBy(PlaybackController.SWITCH_RETRY_DELAY_MILLIS - 1)
        runCurrent()
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(1, engine.startedUrls.size)

        // 5 秒无进展：丢掉字节源后以同一目标重发一次。
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, discards)
        assertEquals(2, engine.startedUrls.size)
        assertEquals(PlaybackState.Loading, controller.state.value)

        // 10 秒仍无进展：按首次打开的失败收尾，不在加载中干等。
        advanceTimeBy(
            PlaybackController.SWITCH_FAIL_DELAY_MILLIS - PlaybackController.SWITCH_RETRY_DELAY_MILLIS,
        )
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
        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS * 6)
        runCurrent()
        assertTrue(failures.isEmpty())
        assertEquals(PlaybackState.Loading, controller.state.value)

        controller.setAppInForeground(true)
        advanceTimeBy(PlaybackController.SWITCH_FAIL_DELAY_MILLIS - 1_000 - 1)
        runCurrent()
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures.size)
    }

    private fun TestScope.controller(
        probe: suspend (MediaFile) -> Boolean = alwaysReadableProbe,
        discard: suspend () -> Boolean = { true },
    ): PlaybackController = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        nowMillis = { testScheduler.currentTime },
        probeTarget = probe,
        discardByteSources = discard,
    )

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
