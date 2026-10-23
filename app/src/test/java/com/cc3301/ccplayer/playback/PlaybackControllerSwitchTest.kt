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
    fun `the buffer indicator delay is one second`() {
        assertEquals(1000L, PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS)
    }

    @Test
    fun `initial loading shows the indicator after the buffer delay`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        assertFalse(controller.buffering.value)

        advanceTimeBy(999)
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
        advanceTimeBy(900)
        runCurrent()
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertFalse(controller.buffering.value)
        advanceTimeBy(1000)
        runCurrent()
        assertFalse(controller.buffering.value)
    }

    @Test
    fun `a first frame exactly at the deadline keeps the indicator hidden`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        advanceTimeBy(1000)
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        runCurrent()

        assertFalse(controller.buffering.value)
        advanceTimeBy(1000)
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
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)

        controller.next()

        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        // 抓帧必须早于 loadfile，否则冻结画面会来自目标文件。
        assertEquals(listOf("pause", "grab", "start"), engine.actions.takeLast(3))
        assertEquals(1, engine.pauseCalls)
        assertEquals(true, engine.startedPaused.last())
        assertSame(frame, controller.freezeFrame.value)
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

        advanceTimeBy(999)
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
    fun `retargeting keeps a pause pressed after the switch started`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")

        controller.next()
        controller.pause()
        controller.next()

        engine.reportFileLoaded()
        engine.reportVideoReconfigured()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)
        assertEquals(2, controller.currentIndex.value)
    }

    @Test
    fun `an end from the old generation does not auto play the next episode`() = runTest {
        val controller = playingController()
        val oldToken = engine.currentLoadToken

        controller.next()
        val started = engine.startedUrls.toList()
        engine.reportPlaybackEnded(loadToken = oldToken)

        assertEquals(started, engine.startedUrls)
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `pausing after the target started settles the switch paused and keeps the freeze`() = runTest {
        val controller = playingController()
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        assertTrue(controller.switching.value)
        assertEquals(1, engine.resumeCalls)

        // 就绪后又暂停：不能再等位置前进，直接按已就绪暂停收尾。
        controller.pause()

        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(frame, controller.freezeFrame.value)

        // 用户继续播放后真正出帧，冻结画面才撤。
        controller.resume()
        engine.reportPauseChanged(false)
        engine.reportPositionChanged(1_000, 600_000)
        engine.reportFramePresented()
        assertNull(controller.freezeFrame.value)
    }

    @Test
    fun `a paused switch starts playing even when it began paused`() = runTest {
        val controller = playingController()
        controller.pause()

        controller.next()
        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(1, engine.resumeCalls)
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
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)

        controller.next()
        controller.next()

        assertEquals(2, controller.currentIndex.value)
        assertEquals("root/ep3.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertTrue(controller.switching.value)
        // 重定目标不再抓帧，冻结画面沿用第一次切集的结果。
        assertSame(frame, controller.freezeFrame.value)

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
    fun `previous back to the origin cancels the switch and restores it paused`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportPositionChanged(40_000, 600_000)

        controller.next()
        controller.previous()

        assertEquals(0, controller.currentIndex.value)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(40_000L, engine.startedPositions.last())
        assertTrue(controller.switching.value)
        assertTrue(controller.restoring.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `automatic next uses the same switch transition as a manual switch`() = runTest {
        val controller = playingController()
        val pauseCallsBefore = engine.pauseCalls

        engine.reportPlaybackEnded()

        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertTrue(controller.switching.value)
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(pauseCallsBefore + 1, engine.pauseCalls)
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
        assertTrue(controller.holdBlack.value)
        assertEquals(PlaybackState.Paused, controller.state.value)

        advanceTimeBy(PlaybackController.LOAD_WATCHDOG_MILLIS)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Paused, controller.state.value)

        // 恢复文件迟到的 END_FILE 不能再次提示“播放失败”。
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(controller.holdBlack.value)

        // 用户重新操作后才解除黑底与失败去重；片尾恢复后播放从 0 重开。
        dispatch(controller, MediaSessionAction.Play)
        assertFalse(controller.holdBlack.value)
        assertTrue(controller.switching.value)
        assertEquals(0L, engine.startedPositions.last())
    }

    @Test
    fun `a natural end marks the origin as ended even when the last position lags`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(500_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()

        assertEquals(500_000L, engine.startedPositions.last())
        assertTrue(controller.switching.value)

        engine.reportFileLoaded()
        assertFalse(controller.switching.value)
        assertTrue(controller.holdBlack.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a manual switch at the end with a grabbed frame waits for a real frame`() = runTest {
        val controller = playingController()
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        engine.reportPositionChanged(600_000, 600_000)

        controller.next()
        assertEquals(frame, controller.freezeFrame.value)

        engine.reportPlaybackFailed()
        assertFalse(controller.holdBlack.value)

        // 抓到了末帧就按冻结帧规则恢复：等原视频重新出帧。
        engine.reportFileLoaded()
        assertTrue(controller.switching.value)
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertFalse(controller.holdBlack.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a manual switch at the end without a frame restores black paused`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)

        controller.next()
        assertNull(controller.freezeFrame.value)
        engine.reportPlaybackFailed()

        // 抓不到末帧时按位置兜底：片尾恢复等文件加载完成，黑底暂停。
        engine.reportFileLoaded()
        assertFalse(controller.switching.value)
        assertTrue(controller.holdBlack.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `an end restore with a lagging position still restarts from the beginning`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(590_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        assertTrue(controller.restoring.value)

        // 自然播完的末次位置可能滞后；播放仍应从头重开而不是续播到片尾。
        dispatch(controller, MediaSessionAction.Play)
        engine.reportFileLoaded()

        assertTrue(controller.switching.value)
        assertEquals(0L, engine.startedPositions.last())
        assertFalse(controller.holdBlack.value)
    }

    @Test
    fun `a seek clears the end restore black hold`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertTrue(controller.holdBlack.value)

        controller.updateSeekPreview(0)
        controller.commitSeekPreview()

        assertFalse(controller.holdBlack.value)
        assertEquals(0L, engine.seekPositions.last())
    }

    @Test
    fun `stop clears the end restore black hold`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertTrue(controller.holdBlack.value)

        controller.stop()

        assertFalse(controller.holdBlack.value)
    }

    @Test
    fun `leaving after an end restore does not save the finished episode position`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(590_000, 600_000)
        runCurrent()
        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertTrue(controller.holdBlack.value)
        progress.records.clear()

        controller.stop()
        runCurrent()

        assertNull(progress.records["root/ep1.mkv"])
    }

    @Test
    fun `leaving before the end restore finishes does not save the finished episode`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(590_000, 600_000)
        runCurrent()

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        assertTrue(controller.restoring.value)
        progress.records.clear()

        controller.stop()
        runCurrent()

        assertNull(progress.records["root/ep1.mkv"])
    }

    @Test
    fun `a late FILE_LOADED for the abandoned target is ignored during restore`() = runTest {
        val controller = playingController()
        controller.next()
        controller.cancelSwitch()
        controller.setRenderSurfaceAvailable(false)

        // 被取消目标的迟到 FILE_LOADED 不能把恢复判成就绪。
        engine.reportFileLoaded(targetUrl = "root/ep2.mkv")
        assertTrue(controller.switching.value)

        engine.reportFileLoaded(targetUrl = "root/ep1.mkv")
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a late event from a same URL reload is ignored`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv")
        engine.reportMediaLoaded()
        controller.setLoopMode(LoopMode.SingleLoop)

        engine.reportPlaybackEnded()
        assertTrue(controller.switching.value)
        val staleToken = engine.currentLoadToken - 1

        // 单集重播与旧加载 URL 相同，只能靠代际区分。
        engine.reportFileLoaded(targetUrl = "root/ep1.mkv", loadToken = staleToken)
        engine.reportFileLoaded()
        engine.reportFirstFrameReady(targetUrl = "root/ep1.mkv", loadToken = staleToken)
        assertTrue(controller.switching.value)

        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `playing during an end restore restarts the original from the beginning`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)
        engine.reportPositionChanged(600_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)
        assertTrue(controller.restoring.value)

        // 用户在恢复期间经媒体控制按了播放：允许下发，且恢复后从头重开而不是片尾 resume。
        dispatch(controller, MediaSessionAction.Play)
        engine.reportFileLoaded()

        assertTrue(controller.switching.value)
        assertEquals(0L, engine.startedPositions.last())
        assertFalse(controller.holdBlack.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 后续真实失败仍会上报。
        engine.reportPlaybackFailed()
        assertEquals(2, failures.size)
    }

    @Test
    fun `play during the target load is blocked and the switch still auto-plays`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)
        controller.pause()

        controller.next()
        assertTrue(controller.switching.value)
        assertFalse(controller.restoring.value)
        dispatch(controller, MediaSessionAction.Play)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `a real failure after a successful restore is reported`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)
        engine.reportPositionChanged(40_000, 600_000)

        controller.next()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertFalse(controller.holdBlack.value)

        engine.reportPlaybackFailed()
        assertEquals(2, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `an automatic switch that grabbed a frame still restores at the end on file load`() = runTest {
        val controller = playingController()
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        engine.reportPositionChanged(590_000, 600_000)

        engine.reportPlaybackEnded()
        assertEquals(frame, controller.freezeFrame.value)

        engine.reportPlaybackFailed()
        assertTrue(controller.switching.value)

        // 自然播完标记优先：即使抓到了帧，片尾恢复仍以文件加载完成为就绪。
        engine.reportFileLoaded()
        assertFalse(controller.switching.value)
        assertTrue(controller.holdBlack.value)
        assertNull(controller.freezeFrame.value)
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
    fun `a grabbed frame stays frozen until the target frame reaches the surface`() = runTest {
        val controller = playingController()
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)

        controller.next()

        assertSame(frame, controller.freezeFrame.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        // 就绪信号先到也不能撤冻结画面：等新帧真正上屏。
        assertSame(frame, controller.freezeFrame.value)
        assertEquals(PlaybackState.Playing, controller.state.value)

        engine.reportFramePresented()
        assertNull(controller.freezeFrame.value)
    }

    @Test
    fun `switching to an audio file does not leave the frozen video frame on screen`() = runTest {
        val controller = playingController()
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)

        controller.next()
        assertSame(frame, controller.freezeFrame.value)

        engine.hasVideoTrack = false
        engine.reportMediaLoaded()

        // 纯音频没有新视频帧可等，冻结画面按黑底处理。
        assertFalse(controller.switching.value)
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
        advanceTimeBy(999)
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
        assertEquals(PlaybackState.Playing, controller.state.value)
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
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
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
        assertFalse(controller.switching.value)
        // 暂停态没有位置前进，冻结画面留到真正出帧。
        assertEquals(frame, controller.freezeFrame.value)

        engine.reportFramePresented()
        assertNull(controller.freezeFrame.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a paused switch keeps the frozen frame until the target frame reaches the surface`() = runTest {
        val controller = playingController()
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        controller.pause()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        runCurrent()

        // 视频链已重配、离屏也能抓到帧，但画面还没上屏：冻结画面不能撤。
        assertSame(frame, controller.freezeFrame.value)
        assertTrue(controller.switching.value)

        engine.reportFramePresented()

        assertNull(controller.freezeFrame.value)
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a paused initial load settles without waiting for a frame`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv")
        controller.pause()
        engine.reportFileLoaded()

        // 首次打开没有冻结画面可撤；暂停意图在文件就绪后直接生效。
        engine.reportPausedRestart()
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a switch settles ready two seconds after file load without a frame signal`() = runTest {
        val controller = playingController()

        controller.next()
        engine.reportFileLoaded()
        assertTrue(controller.switching.value)

        // 加载完成后画面信号一直不来：2 秒后按就绪收尾并开始播放。
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS - 1)
        runCurrent()
        assertTrue(controller.switching.value)
        assertEquals(0, engine.resumeCalls)

        advanceTimeBy(1)
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `a switch settles ready after two seconds without a presented frame`() = runTest {
        val controller = playingController()
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()

        // 没有位置前进：2 秒后按就绪收尾，不再等 60 秒看门狗。
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS - 1)
        runCurrent()
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertTrue(failures.isEmpty())
        // 没有新帧证据：冻结画面继续保留。
        assertSame(frame, controller.freezeFrame.value)

        engine.reportFramePresented()
        assertNull(controller.freezeFrame.value)
    }

    @Test
    fun `a delayed frame on the surface still completes the switch`() = runTest {
        val controller = playingController()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        runCurrent()
        assertTrue(controller.switching.value)

        engine.reportFramePresented()
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
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

        assertEquals(PlaybackState.Playing, controller.state.value)
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
    fun `a frame on the surface before the buffer delay keeps the indicator hidden`() = runTest {
        val controller = playingController()
        controller.pause()

        controller.next()
        advanceTimeBy(900)
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        runCurrent()
        engine.reportFramePresented()
        runCurrent()

        assertFalse(controller.buffering.value)
        advanceTimeBy(1000)
        runCurrent()
        assertFalse(controller.buffering.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
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
        assertEquals(1, engine.discardInFlightCalls)
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
    fun `the indicator appears while the surface frame is still missing`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        advanceTimeBy(980)
        engine.reportFileLoaded()
        // 文件已打开但首帧信号还没到：不能取消计时，也不能算就绪。
        assertFalse(controller.buffering.value)
        assertEquals(PlaybackState.Loading, controller.state.value)

        advanceTimeBy(20)
        runCurrent()
        assertTrue(controller.buffering.value)
        assertEquals(PlaybackState.Loading, controller.state.value)

        // 首帧信号到达后才撤掉提示并显示画面。
        engine.reportFirstFrameReady()
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
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `resuming during a restore continues after the first frame`() = runTest {
        val controller = playingController()

        controller.next()
        controller.cancelSwitch()
        assertTrue(controller.restoring.value)
        dispatch(controller, MediaSessionAction.Play)
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
        runCurrent()

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
    fun `previous and next are ignored while the initial load is in flight`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv", "ep2.mkv")
        controller.next()
        controller.previous()
        controller.playAt(1)
        controller.play(MediaFile(name = "ep2.mkv", location = "root/ep2.mkv"))

        // 首次缓冲期间只允许返回：不会转到切集路径。
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertEquals(0, controller.currentIndex.value)
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
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
        engine.reportMediaLoaded()

        controller.play(MediaFile(name = "ep10.mkv", location = "root/ep10.mkv"))

        assertEquals(2, controller.currentIndex.value)
        assertEquals("root/ep10.mkv", engine.startedUrls.last())
    }

    @Test
    fun `previous really plays the previous episode`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv", current = "ep2.mkv")
        engine.reportMediaLoaded()

        controller.previous()

        assertEquals(0, controller.currentIndex.value)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
    }

    private fun dispatch(controller: PlaybackController, action: MediaSessionAction) {
        dispatchMediaAction(
            action,
            controller.state.value,
            controller.switching.value,
            controller.restoring.value,
        ) { it.applyTo(controller) }
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
}
