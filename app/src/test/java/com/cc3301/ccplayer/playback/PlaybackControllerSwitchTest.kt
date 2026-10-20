package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import com.cc3301.ccplayer.progress.MediaProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切集与缓冲指示的状态机：500ms 阈值、首帧判定、失败与取消回退、
 * 替换事件不触发连播，以及加载超时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSwitchTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `initial loading shows the indicator only after 500ms`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        assertFalse(controller.buffering.value)

        advanceTimeBy(BUFFER_DELAY - 1)
        runCurrent()
        assertFalse(controller.buffering.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(controller.buffering.value)
    }

    @Test
    fun `a first frame within 500ms never shows the indicator`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        advanceTimeBy(BUFFER_DELAY - 100)
        runCurrent()
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertFalse(controller.buffering.value)
        advanceTimeBy(BUFFER_DELAY)
        runCurrent()
        assertFalse(controller.buffering.value)
    }

    @Test
    fun `a first frame exactly at the deadline keeps the indicator hidden`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        advanceTimeBy(BUFFER_DELAY)
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        runCurrent()

        assertFalse(controller.buffering.value)
        advanceTimeBy(BUFFER_DELAY)
        runCurrent()
        assertFalse(controller.buffering.value)
    }

    @Test
    fun `a new session explicitly starts the engine unpaused`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv")
        controller.pause()
        controller.stop()

        open(controller, "ep1.mkv")

        assertEquals(false, engine.startedPaused.last())
    }

    @Test
    fun `switching pauses the old video and grabs a frame before loading the target`() = runTest {
        val controller = playingController()

        controller.next()

        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(listOf("pause", "grab", "start"), engine.actions.takeLast(3))
        assertEquals(true, engine.startedPaused.last())
        assertEquals(1, engine.grabFrameCalls)
        assertEquals(0, engine.resumeCalls)
        assertTrue(controller.switching.value)
    }

    @Test
    fun `the target stays paused until its first frame is ready`() = runTest {
        val controller = playingController()

        controller.next()
        engine.reportFileLoaded()
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(0, engine.resumeCalls)

        engine.reportFirstFrameReady()

        assertEquals(1, engine.resumeCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertFalse(controller.switching.value)
        assertNull(controller.freezeFrame.value)
    }

    @Test
    fun `switching uses the same 500ms indicator threshold`() = runTest {
        val controller = playingController()

        controller.next()
        assertFalse(controller.buffering.value)

        advanceTimeBy(BUFFER_DELAY - 1)
        runCurrent()
        assertFalse(controller.buffering.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(controller.buffering.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.buffering.value)
    }

    @Test
    fun `a paused switch stays paused after the first frame`() = runTest {
        val controller = playingController()
        controller.pause()

        controller.next()
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)
    }

    @Test
    fun `the switch completes on the first frame of the target`() = runTest {
        val controller = playingController()

        controller.next()
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertFalse(controller.switching.value)
        assertFalse(controller.buffering.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(0L, controller.progress.value.positionMillis)
    }

    @Test
    fun `repeated switches during buffering do nothing`() = runTest {
        val controller = playingController()

        controller.next()
        controller.previous()
        controller.playAt(0)
        controller.play(MediaFile(name = "ep2.mkv", location = "root/ep2.mkv"))

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(1, engine.grabFrameCalls)
    }

    @Test
    fun `a failed switch returns to the original video paused at its position`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)

        controller.next()
        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertFalse(controller.buffering.value)
        assertEquals(40_000L, controller.progress.value.positionMillis)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertEquals(40_000L, engine.startedPositions.last())

        engine.reportFileLoaded()
        assertTrue(controller.switching.value)
        assertNull(controller.freezeFrame.value)

        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a failed switch reports playback failure`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportPlaybackFailed()

        assertEquals(1, failures.size)
    }

    @Test
    fun `back cancels the switch and leaves the original video paused`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(12_000, 600_000)

        controller.next()

        assertTrue(controller.cancelSwitch())
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(12_000L, controller.progress.value.positionMillis)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertFalse(controller.cancelSwitch())
        assertEquals(12_000L, engine.startedPositions.last())

        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        assertTrue(controller.switching.value)

        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        advanceTimeBy(PlaybackController.FRAME_PROBE_INTERVAL_MILLIS)
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a paused switch needs a grabbed frame after video reconfig`() = runTest {
        val controller = playingController()
        controller.pause()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()

        // 只有重配、抓不到帧时不能撤冻结层。
        assertTrue(controller.switching.value)
        advanceTimeBy(PlaybackController.FRAME_PROBE_INTERVAL_MILLIS)
        runCurrent()
        assertTrue(controller.switching.value)

        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        advanceTimeBy(PlaybackController.FRAME_PROBE_INTERVAL_MILLIS)
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `back during the initial load is not a switch cancellation`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")

        assertFalse(controller.cancelSwitch())
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `pausing before the first frame keeps the initial load pending then paused`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        engine.reportPauseChanged(true)
        assertEquals(PlaybackState.Loading, controller.state.value)

        engine.reportFileLoaded()
        assertEquals(PlaybackState.Loading, controller.state.value)

        engine.reportFirstFrameReady()
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `an end event while switching does not auto play the next episode`() = runTest {
        val controller = playingController()

        controller.next()
        val started = engine.startedUrls.toList()
        engine.reportPlaybackEnded()

        assertEquals(started, engine.startedUrls)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `a load that never emits any event is caught by the watchdog`() = runTest {
        val controller = controller()
        val failures = collectFailures(controller)

        open(controller, "ep1.mkv")
        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()

        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(1, failures.size)
    }

    @Test
    fun `a switch that never emits any event is caught by the watchdog`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(1, failures.size)
    }

    @Test
    fun `load activity resets the watchdog`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS - 1)
        engine.reportLoadActivity()
        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS - 1)
        runCurrent()
        assertEquals(PlaybackState.Loading, controller.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `a first frame before 500ms cancels the indicator even if the probe finishes later`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        advanceTimeBy(PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS - 20)
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()

        // 就绪信号已到，计时被取消；抓帧探测可以晚于 500ms。
        advanceTimeBy(PlaybackController.FRAME_PROBE_INTERVAL_MILLIS)
        runCurrent()
        assertFalse(controller.buffering.value)

        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        advanceTimeBy(PlaybackController.FRAME_PROBE_INTERVAL_MILLIS)
        runCurrent()
        assertFalse(controller.buffering.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a late ready from the cancelled target is ignored during restore`() = runTest {
        val controller = playingController()

        controller.next()
        controller.cancelSwitch()
        engine.reportFileLoaded()

        engine.reportFirstFrameReady(targetUrl = "root/ep2.mkv")
        assertTrue(controller.switching.value)

        engine.reportVideoReconfigured()
        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        advanceTimeBy(PlaybackController.FRAME_PROBE_INTERVAL_MILLIS)
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a resume seek is applied before the ready frame is accepted`() = runTest {
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        val controller = playingController()
        runCurrent()

        controller.next()
        runCurrent()
        engine.reportFileLoaded()

        assertEquals(20_000L, engine.seekPositions.last())

        // 帧已可取，但续播 seek 刚发出：先等一个探测间隔再认首帧。
        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        engine.reportVideoReconfigured()
        assertTrue(controller.switching.value)

        advanceTimeBy(PlaybackController.FRAME_PROBE_INTERVAL_MILLIS)
        runCurrent()
        assertFalse(controller.switching.value)
    }

    @Test
    fun `an initial load failure stops the engine and reopening starts cleanly`() = runTest {
        val controller = controller()
        val failures = collectFailures(controller)

        open(controller, "ep1.mkv")
        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(1, failures.size)
        assertEquals(1, engine.stopCalls)

        open(controller, "ep1.mkv")
        assertEquals(PlaybackState.Loading, controller.state.value)
        engine.reportMediaLoaded()
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(2, engine.startedUrls.size)
    }

    @Test
    fun `a playback failure ends the session and the play key reopens the file`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(1, failures.size)

        controller.togglePlayPause()

        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `switching is accepted while the initial load is still in flight`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv", "ep2.mkv")
        controller.next()

        assertEquals("root/ep2.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertEquals(1, controller.currentIndex.value)
    }

    @Test
    fun `a failing restore ends the session and reports failure`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)

        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(2, failures.size)
        assertFalse(controller.switching.value)
        assertNull(controller.freezeFrame.value)
    }

    @Test
    fun `stopping during a switch saves the original position`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(33_000, 600_000)
        runCurrent()
        progress.records.clear()

        controller.next()
        controller.stop()
        runCurrent()

        assertEquals(33_000L, progress.records["root/ep1.mkv"]?.positionMillis)
    }

    @Test
    fun `playlist selection resolves the target by media location`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", "ep10.mkv")

        controller.play(MediaFile(name = "ep10.mkv", location = "root/ep10.mkv"))

        assertEquals(2, controller.currentIndex.value)
        assertEquals("root/ep10.mkv", engine.startedUrls.last())
    }

    @Test
    fun `previous really plays the previous episode`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep2.mkv")

        controller.previous()

        assertEquals(0, controller.currentIndex.value)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
    }

    private fun TestScope.controller(): PlaybackController =
        PlaybackController(engine, progress, backgroundScope)

    /** 起播第一集并等首帧就绪，作为切集前的正常播放状态。 */
    private fun TestScope.playingController(): PlaybackController {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv")
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

    private companion object {
        const val BUFFER_DELAY = PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS
    }
}
