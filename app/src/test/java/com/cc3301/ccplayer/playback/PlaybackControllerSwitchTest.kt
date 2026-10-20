package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
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
 * 切集与缓冲指示的状态机：500ms 阈值、失败与取消回退、替换事件不触发连播。
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
        engine.reportMediaLoaded()

        assertFalse(controller.buffering.value)
        advanceTimeBy(BUFFER_DELAY)
        runCurrent()
        assertFalse(controller.buffering.value)
    }

    @Test
    fun `switching pauses the old video before loading the target`() = runTest {
        val controller = playingController()

        controller.next()

        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertTrue(engine.pauseCalls >= 1)
        assertTrue(controller.switching.value)
    }

    @Test
    fun `the old audio stays paused until the target file is loaded`() = runTest {
        val controller = playingController()

        controller.next()

        assertEquals(0, engine.resumeCalls)
        engine.reportFileLoaded()
        assertEquals(1, engine.resumeCalls)
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

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(1, controller.currentIndex.value)
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

        engine.reportFileLoaded()
        assertEquals(40_000L, engine.seekPositions.last())
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertFalse(controller.switching.value)
    }

    @Test
    fun `a failed switch reports playback failure`() = runTest {
        val controller = playingController()
        val failures = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.playbackFailures.collect { failures += it }
        }

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

        engine.reportFileLoaded()
        assertFalse(controller.switching.value)
        assertEquals(12_000L, engine.seekPositions.last())
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
    fun `an end event while switching does not auto play the next episode`() = runTest {
        val controller = playingController()

        controller.next()
        val started = engine.startedUrls.toList()
        engine.reportPlaybackEnded()

        assertEquals(started, engine.startedUrls)
        assertEquals(PlaybackState.Loading, controller.state.value)
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
        advanceTimeBy(BUFFER_DELAY)
        runCurrent()
        engine.reportMediaLoaded()
        return controller
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
        const val BUFFER_DELAY = 500L
    }
}
