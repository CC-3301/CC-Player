package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切集与缓冲指示的状态机：缓冲延迟阈值、首帧判定、失败与取消回退、
 * 替换事件不触发连播，以及加载超时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSwitchTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `initial loading shows the indicator after the buffer delay`() = runTest {
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
    fun `a first frame within the buffer delay never shows the indicator`() = runTest {
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
    fun `switching pauses the old video and grabs a frame`() = runTest {
        val controller = playingController()

        controller.next()

        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(1, engine.pauseCalls)
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
    fun `switching uses the same indicator threshold`() = runTest {
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
    fun `repeated next during a switch advances the target to the final episode`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")

        controller.next()
        controller.next()

        assertEquals(2, controller.currentIndex.value)
        assertEquals("root/ep3.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertTrue(controller.switching.value)
        // 重定目标不再抓帧，冻结画面沿用第一次切集的结果。
        assertEquals(1, engine.grabFrameCalls)

        // 被跳过的第 2 集迟到的就绪不能结束切换。
        engine.reportFirstFrameReady(targetUrl = "root/ep2.mkv")
        assertTrue(controller.switching.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(2, controller.currentIndex.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `playlist selection is ignored during a switch`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")

        controller.next()
        controller.playAt(2)
        controller.play(MediaFile(name = "ep3.mkv", location = "root/ep3.mkv"))

        assertEquals(1, controller.currentIndex.value)
        assertEquals("root/ep2.mkv", engine.startedUrls.last())
        assertTrue(controller.switching.value)
    }

    @Test
    fun `previous during a switch moves the target back`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")

        controller.next()
        controller.previous()

        assertEquals(0, controller.currentIndex.value)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertTrue(controller.switching.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `automatic next uses the same switch transition as a manual switch`() = runTest {
        val controller = playingController()
        val pauseCallsBefore = engine.pauseCalls
        val grabCallsBefore = engine.grabFrameCalls

        engine.reportPlaybackEnded()

        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertTrue(controller.switching.value)
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(pauseCallsBefore + 1, engine.pauseCalls)
        assertEquals(grabCallsBefore + 1, engine.grabFrameCalls)
        assertEquals(true, engine.startedPaused.last())
        assertEquals(0, engine.resumeCalls)
        // 自然播完后已无帧可抓：冻结画面为空，界面按黑底等待。
        assertNull(controller.freezeFrame.value)
    }

    @Test
    fun `automatic next completes on the target first frame instead of the watchdog`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        engine.reportPlaybackEnded()
        engine.reportFileLoaded()
        assertTrue(controller.switching.value)
        assertEquals(0, engine.resumeCalls)

        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(1, engine.resumeCalls)

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `automatic next with a resume record completes after the seek`() = runTest {
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        val controller = playingController()

        engine.reportPlaybackEnded()
        engine.reportFileLoaded()

        assertEquals(20_000L, engine.seekPositions.last())

        engine.reportFirstFrameReady(seekSerial = engine.currentSeekSerial - 1)
        assertTrue(controller.switching.value)

        engine.reportFirstFrameReady(seekSerial = engine.currentSeekSerial)
        assertFalse(controller.switching.value)
        assertEquals(20_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `a failed automatic switch restores the original video paused at its position`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)
        engine.reportPositionChanged(590_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(590_000L, controller.progress.value.positionMillis)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertEquals(590_000L, engine.startedPositions.last())
        assertEquals(1, failures.size)
    }

    @Test
    fun `a failed automatic switch at the end restores a black paused frame on file load`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)
        engine.reportPositionChanged(600_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(600_000L, controller.progress.value.positionMillis)
        assertEquals(600_000L, engine.startedPositions.last())
        assertTrue(controller.switching.value)
        assertNull(controller.freezeFrame.value)
        assertEquals(1, failures.size)

        // 片尾恢复没有首帧可等：文件加载完成即结束恢复，保持黑底暂停。
        engine.reportFileLoaded()
        assertFalse(controller.switching.value)
        assertNull(controller.freezeFrame.value)
        assertEquals(PlaybackState.Paused, controller.state.value)

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a restore that times out after a failed switch reports failure only once`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)
        engine.reportPositionChanged(600_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()

        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `a failed retarget restores the original video position`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        controller.next()
        controller.next()
        engine.reportPlaybackFailed()

        assertEquals(0, controller.currentIndex.value)
        assertEquals(40_000L, controller.progress.value.positionMillis)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertEquals(40_000L, engine.startedPositions.last())
        assertEquals(1, failures.size)
    }

    @Test
    fun `a grabbed frame stays frozen until the target is ready`() = runTest {
        val controller = playingController()
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)

        controller.next()

        assertSame(frame, controller.freezeFrame.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertNull(controller.freezeFrame.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a manual switch leaves the origin progress untouched until the target is ready`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)

        controller.next()

        assertEquals(PlaybackProgress(40_000, 600_000), controller.progress.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(0L, controller.progress.value.positionMillis)
    }

    @Test
    fun `retargeting does not restart the buffering indicator timer`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")

        controller.next()
        advanceTimeBy(PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS - 1)
        runCurrent()
        assertFalse(controller.buffering.value)

        controller.next()
        runCurrent()
        assertFalse(controller.buffering.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(controller.buffering.value)
    }

    @Test
    fun `next during a restore starts a switch from the origin`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportPositionChanged(40_000, 600_000)
        controller.next()
        engine.reportPlaybackFailed()

        controller.next()

        assertEquals(1, controller.currentIndex.value)
        assertEquals("root/ep2.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
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
        engine.reportVideoReconfigured()
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

        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        engine.reportVideoReconfigured()
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a paused restart also needs a grabbed frame`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv")
        controller.pause()
        engine.reportFileLoaded()

        // 暂停态的 PLAYBACK_RESTART 不能单独证明画面已上屏。
        engine.reportPausedRestart()
        assertEquals(PlaybackState.Loading, controller.state.value)
        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS)
        runCurrent()
        assertEquals(PlaybackState.Loading, controller.state.value)

        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS)
        runCurrent()
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a failed frame probe keeps waiting instead of failing the load`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()

        // 抓帧探测没有独立短超时：远超旧探测预算后仍停在加载中。
        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS - 1)
        runCurrent()
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertTrue(failures.isEmpty())

        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        engine.reportVideoReconfigured()
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a paused probe retries until a frame appears`() = runTest {
        val controller = playingController()
        controller.pause()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        runCurrent()
        val attempts = engine.grabFrameCalls

        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS)
        runCurrent()
        assertEquals(attempts + 1, engine.grabFrameCalls)
        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS)
        runCurrent()
        assertEquals(attempts + 2, engine.grabFrameCalls)
        assertTrue(controller.switching.value)

        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS)
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a paused switch with resume keeps the target position in progress`() = runTest {
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        val controller = playingController()
        controller.pause()

        controller.next()
        engine.reportFileLoaded()
        engine.reportPositionChanged(20_000, 600_000)
        engine.reportFirstFrameReady(seekSerial = engine.currentSeekSerial)

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(20_000L, controller.progress.value.positionMillis)
        assertEquals(600_000L, controller.progress.value.durationMillis)
    }

    @Test
    fun `an initial load with resume and pause keeps the position in progress`() = runTest {
        progress.records["root/ep1.mkv"] = MediaProgress("root/ep1.mkv", 30_000, 600_000, 0)
        val controller = controller()

        open(controller, "ep1.mkv")
        controller.pause()
        engine.reportFileLoaded()
        engine.reportPositionChanged(30_000, 600_000)
        engine.reportFirstFrameReady(seekSerial = engine.currentSeekSerial)

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(30_000L, controller.progress.value.positionMillis)
        assertEquals(600_000L, controller.progress.value.durationMillis)
    }

    @Test
    fun `an initial load without a render surface completes on file load`() = runTest {
        val controller = controller()
        controller.setRenderSurfaceAvailable(false)

        open(controller, "ep1.mkv")
        engine.reportFileLoaded()

        assertFalse(controller.buffering.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a cancelled switch without a render surface finishes the restore on file load`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)
        controller.setRenderSurfaceAvailable(false)

        controller.next()
        controller.cancelSwitch()
        engine.reportFileLoaded()

        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a switch that regains a render surface waits for a frame again`() = runTest {
        val controller = playingController()
        controller.setRenderSurfaceAvailable(false)

        controller.next()
        controller.setRenderSurfaceAvailable(true)
        engine.reportFileLoaded()

        // Surface 回来后不再按“文件加载完成”就绪，等真正的首帧。
        assertTrue(controller.switching.value)
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a grab before the buffer delay keeps the indicator hidden`() = runTest {
        val controller = playingController()
        controller.pause()

        controller.next()
        advanceTimeBy(PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS - 100)
        engine.reportFileLoaded()
        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        engine.reportVideoReconfigured()

        assertFalse(controller.buffering.value)
        advanceTimeBy(PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS)
        runCurrent()
        assertFalse(controller.buffering.value)
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
    fun `the indicator appears when the probe crosses the buffer delay`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        advanceTimeBy(PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS - 20)
        engine.reportFileLoaded()
        // 重配已到但抓不到帧：不能取消计时，也不能算就绪。
        engine.reportVideoReconfigured()
        assertFalse(controller.buffering.value)
        assertEquals(PlaybackState.Loading, controller.state.value)

        advanceTimeBy(20)
        runCurrent()
        assertTrue(controller.buffering.value)
        assertEquals(PlaybackState.Loading, controller.state.value)

        // 抓帧成功才撤掉提示并显示画面。
        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        engine.reportVideoReconfigured()
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
        assertTrue(controller.switching.value)
        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        engine.reportVideoReconfigured()
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `resuming during a restore continues after the first frame`() = runTest {
        val controller = playingController()

        controller.next()
        controller.cancelSwitch()
        controller.resume()
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `a ready signal from before the resume seek is ignored`() = runTest {
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        val controller = playingController()

        controller.next()
        engine.reportFileLoaded()

        assertEquals(20_000L, engine.seekPositions.last())
        val seekSerial = engine.currentSeekSerial

        // seek 之前的信号序号更小：不能结束加载。
        engine.reportFirstFrameReady(seekSerial = seekSerial - 1)
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)

        // seek 之后引擎会再次上报 PLAYBACK_RESTART，这一条才算就绪。
        engine.reportFirstFrameReady(seekSerial = seekSerial)
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `readiness waits for the resume lookup so the seek happens first`() = runTest {
        progress.records["root/ep1.mkv"] = MediaProgress("root/ep1.mkv", 30_000, 600_000, 0)
        progress.holdFinds()
        val controller = controller()

        open(controller, "ep1.mkv")
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        // 续播位置还没查到，不能判定就绪，也不能先起播。
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertTrue(engine.seekPositions.isEmpty())

        progress.releaseFinds()
        runCurrent()

        assertEquals(30_000L, engine.seekPositions.last())
        val seekSerial = engine.currentSeekSerial
        assertEquals(PlaybackState.Loading, controller.state.value)

        engine.reportFirstFrameReady(seekSerial = seekSerial - 1)
        assertEquals(PlaybackState.Loading, controller.state.value)

        engine.reportFirstFrameReady(seekSerial = seekSerial)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a ready received during the resume lookup still counts when there is no record`() = runTest {
        progress.holdFinds()
        val controller = controller()

        open(controller, "ep1.mkv")
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertEquals(PlaybackState.Loading, controller.state.value)

        progress.releaseFinds()
        runCurrent()

        // 没有续播位置就不需要 seek，查询前收到的首帧直接生效。
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a cancelled switch drops the target's late resume lookup`() = runTest {
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        val controller = playingController()
        progress.holdFinds()

        controller.next()
        controller.cancelSwitch()
        progress.releaseFinds()
        runCurrent()

        // 被取消目标的迟到查询不能再对恢复后的原视频发出 seek。
        assertTrue(engine.seekPositions.isEmpty())

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a switch without a render surface completes on file load`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(15_000, 600_000)
        controller.setRenderSurfaceAvailable(false)

        controller.next()
        engine.reportFileLoaded()

        // 后台抓不到帧不算失败：文件加载完成即就绪。
        assertEquals(1, controller.currentIndex.value)
        assertFalse(controller.switching.value)
        assertFalse(controller.buffering.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertNull(controller.freezeFrame.value)

        // 回前台重挂 Surface 不应重新进入加载。
        controller.setRenderSurfaceAvailable(true)
        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
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
    fun `a failing restore ends the session without repeating the failure`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)

        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(1, failures.size)
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

    /** 抓帧用测试调度器，和虚拟时间一起推进；生产环境由控制器切到后台线程。 */
    private fun TestScope.controller(): PlaybackController =
        PlaybackController(
            engine,
            progress,
            backgroundScope,
            frameProbeDispatcher = UnconfinedTestDispatcher(testScheduler),
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

    /**
     * 测试只需要一个非空帧身份；android.jar 的 Bitmap 构造器在单测里是桩，
     * 用 Unsafe 绕过构造器分配实例。
     */
    private fun testBitmap(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    private companion object {
        const val BUFFER_DELAY = PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS
    }
}
