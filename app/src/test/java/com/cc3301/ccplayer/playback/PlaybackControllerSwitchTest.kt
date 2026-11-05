package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.MemoryDiagnosticsSink
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import com.cc3301.ccplayer.progress.MediaProgress
import com.cc3301.ccplayer.ui.showPlayerBlackLayer
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
import org.junit.Assert.assertNull
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
        // 起算点固定为一秒。
        assertEquals(1000L, PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS)

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
    fun `a manual switch keeps the surface frame`() = runTest {
        val controller = playingController()

        controller.next()

        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        // 先停下原视频，再直接加载目标：屏幕上停住的旧帧就是等待画面。
        assertEquals(listOf("pause", "start"), engine.actions.takeLast(2))
        assertEquals(1, engine.pauseCalls)
        assertEquals(true, engine.startedPaused.last())
        assertEquals(0, engine.resumeCalls)
        assertTrue(controller.switching.value)
        // 与片尾自动切集同一条路：沿用屏幕上停住的旧帧，不铺黑底。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
    }

    @Test
    fun `a natural end switch loads the target right away`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv", "ep2.mkv")
        engine.reportMediaLoaded()

        engine.reportPlaybackEnded()

        // 目标立刻开始加载，屏幕上停住的末帧继续显示。
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(listOf("pause", "start"), engine.actions.takeLast(2))
        assertTrue(controller.switching.value)
        // 旧画面仍在屏上：沿用末帧，不铺黑底。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
    }

    @Test
    fun `a switch stops keeping the surface frame once the frame shows`() = runTest {
        val controller = playingController()
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportFramePresented()

        engine.reportPlaybackEnded()
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)

        engine.reportFileLoaded()
        engine.reportVideoSizeChanged(1080, 1920)
        engine.reportFirstFrameReady()

        assertFalse(controller.switching.value)
        // 首帧已可显示：撤掉旧帧，屏上是新画面与新文件比例，不铺黑底。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(1080.0 / 1920.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)
    }

    @Test
    fun `a weak frame signal does not withdraw the frame on screen`() = runTest {
        val controller = playingController()
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportFramePresented()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoSizeChanged(1080, 1920)

        // 需要确认的重配只说明目标接管了视频输出，画面可能还没上屏：屏上的原片画面与比例都不动。
        engine.reportVideoReconfigured()

        assertTrue(controller.switching.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)
    }

    @Test
    fun `a rejected late receipt does not withdraw the frame on screen`() = runTest {
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        val controller = playingController()
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportFramePresented()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoSizeChanged(1080, 1920)

        // 这条信号早于续播那一跳，不在接受范围内：不能撤掉屏上的原片画面与比例。
        engine.reportFirstFrameReady(fromSeek = false)

        assertTrue(controller.switching.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)
    }

    @Test
    fun `a switch to a file without video stops keeping the surface frame`() = runTest {
        val controller = playingController()

        engine.reportPlaybackEnded()
        assertTrue(showsPicture(controller))

        engine.hasVideoTrack = false
        engine.reportMediaLoaded()

        // 目标没有视频轨：切集结束即撤掉沿用标记，黑底按原有规则处理。
        assertFalse(controller.switching.value)
        assertFalse(showsPicture(controller))
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
    fun `pausing after the target started settles the switch paused`() = runTest {
        val controller = playingController()
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportFramePresented()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoSizeChanged(1080, 1920)
        engine.reportVideoReconfigured()
        assertTrue(controller.switching.value)
        assertEquals(1, engine.resumeCalls)
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)

        // 就绪后又暂停：不能再等位置前进，直接按已就绪暂停收尾。
        controller.pause()

        // 暂停态没有更靠后的出帧信号：收尾即撤旧帧，屏上是新集首帧与新文件比例，不铺黑底。
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(1080.0 / 1920.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)
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

        controller.next()
        controller.next()

        assertEquals(2, controller.currentIndex.value)
        assertEquals("root/ep3.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertTrue(controller.switching.value)
        // 重定目标不清屏，屏幕上停住的旧帧继续沿用。
        assertTrue(showsPicture(controller))

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
        // 自然播完不清屏：屏幕上停住的末帧继续显示，不铺黑底。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
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
        // 真正出帧才算目标画面已上屏，之后不再有无帧失败。
        engine.reportFramePresented()

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
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

        // 这条信号不对应任何 seek：它只是加载自身的结果，不能结束切集。
        engine.reportFirstFrameReady(fromSeek = false)
        assertTrue(controller.switching.value)

        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(20_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `a failed automatic switch restores the original video paused at its position`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)
        engine.reportPositionChanged(590_000, 600_000)

        engine.reportPlaybackEnded()
        // 第一次读失败先重发一次，重发也失败才回退原片。
        engine.reportPlaybackFailed()
        runCurrent()
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
        runCurrent()
        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(600_000L, controller.progress.value.positionMillis)
        assertEquals(600_000L, engine.startedPositions.last())
        assertTrue(controller.switching.value)
        // 片尾切集失败回退：不再沿用屏幕上停住的末帧，恢复期间按黑底等待。
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))
        assertEquals(1, failures.size)

        // 片尾恢复没有首帧可等：文件加载完成即结束恢复，保持黑底暂停。
        engine.reportFileLoaded()
        assertFalse(controller.switching.value)
        // 片尾恢复保持黑底。
        assertFalse(showsPicture(controller))
        assertEquals(PlaybackState.Paused, controller.state.value)

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Paused, controller.state.value)

        // 恢复文件迟到的 END_FILE 不能再次提示“播放失败”。
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertFalse(showsPicture(controller))

        // 用户重新操作后才解除黑底与失败去重；片尾恢复后播放从 0 重开。
        dispatch(controller, MediaSessionAction.Play)
        assertTrue(controller.switching.value)
        assertEquals(0L, engine.startedPositions.last())
    }

    @Test
    fun `a natural end marks the origin as ended even when the last position lags`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(500_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()

        assertEquals(500_000L, engine.startedPositions.last())
        assertTrue(controller.switching.value)

        engine.reportFileLoaded()
        assertFalse(controller.switching.value)
        assertFalse(showsPicture(controller))
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a manual switch at the end keeps the old frame while the origin is restored`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)

        controller.next()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()

        // 手动切集的失败回退沿用屏幕上停住的旧帧，不落到片尾黑底那条规则里。
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(controller.restoring.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))

        engine.reportFileLoaded()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)

        // 原片重新出帧后画面回来，不落到片尾黑底那条规则里。
        engine.reportFramePresented()
        assertTrue(showsPicture(controller))
    }

    @Test
    fun `an end restore with a lagging position still restarts from the beginning`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(590_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()
        assertTrue(controller.restoring.value)

        // 自然播完的末次位置可能滞后；播放仍应从头重开而不是续播到片尾。
        dispatch(controller, MediaSessionAction.Play)
        engine.reportFileLoaded()

        assertTrue(controller.switching.value)
        assertEquals(0L, engine.startedPositions.last())
    }

    @Test
    fun `a seek clears the end restore black hold`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertFalse(showsPicture(controller))

        controller.updateSeekPreview(requestSeek = true, positionMillis = 0)
        controller.commitSeekPreview()

        assertEquals(0L, engine.seekPositions.last())
        // 落定 seek 后重新出帧：画面回来，不再是片尾黑底。
        engine.reportFramePresented()
        assertTrue(showsPicture(controller))
    }

    @Test
    fun `stop clears the end restore black hold`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertFalse(showsPicture(controller))

        controller.stop()

        assertEquals(PlaybackState.Idle, controller.state.value)
        assertFalse(showsPicture(controller))
    }

    @Test
    fun `leaving after an end restore does not save the finished episode position`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(590_000, 600_000)
        runCurrent()
        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertFalse(showsPicture(controller))
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
        runCurrent()
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
        runCurrent()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)
        assertTrue(controller.restoring.value)

        // 用户在恢复期间经媒体控制按了播放：允许下发，且恢复后从头重开而不是片尾 resume。
        dispatch(controller, MediaSessionAction.Play)
        engine.reportFileLoaded()

        assertTrue(controller.switching.value)
        assertEquals(0L, engine.startedPositions.last())

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
        runCurrent()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)

        engine.reportPlaybackFailed()
        assertEquals(2, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `a restore that times out after a failed switch reports failure only once`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)
        engine.reportPositionChanged(600_000, 600_000)

        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
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
        runCurrent()
        engine.reportPlaybackFailed()

        assertEquals(0, controller.currentIndex.value)
        assertEquals(40_000L, controller.progress.value.positionMillis)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertEquals(40_000L, engine.startedPositions.last())
        assertEquals(1, failures.size)
    }

    @Test
    fun `switching to an audio file does not keep the old video frame on screen`() = runTest {
        val controller = playingController()

        controller.next()
        assertTrue(showsPicture(controller))

        engine.hasVideoTrack = false
        engine.reportMediaLoaded()

        // 纯音频没有新视频帧可等，不再沿用旧画面，切完保持黑底。
        assertFalse(controller.switching.value)
        assertFalse(showsPicture(controller))
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertTrue(blackLayer(controller))
    }

    @Test
    fun `retargeting after a target without video keeps the surface frame again`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        controller.next()

        // 目标没有视频轨，但文件刚加载、还没就绪：这一次切集内不再沿用旧画面。
        engine.hasVideoTrack = false
        engine.reportFileLoaded()
        assertTrue(controller.switching.value)
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))

        engine.hasVideoTrack = true
        controller.next()

        // 重定目标到有视频轨的集：按新的目标恢复沿用屏幕上停住的旧帧。
        assertEquals(2, controller.currentIndex.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
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
    fun `next during a restore that still keeps the old frame does not go black`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportPositionChanged(40_000, 600_000)
        controller.next()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()
        assertTrue(controller.restoring.value)
        assertTrue(showsPicture(controller))

        controller.next()

        // 恢复期屏幕上仍是原片画面（沿用旧帧）：这一段切集接着沿用，不铺黑底。
        assertEquals(1, controller.currentIndex.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
    }

    @Test
    fun `next during a restore starts a switch from the origin`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportPositionChanged(40_000, 600_000)
        controller.next()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()

        controller.next()

        assertEquals(1, controller.currentIndex.value)
        assertEquals("root/ep2.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
        // 恢复期屏幕上仍是原片画面：这一路继续沿用，不铺黑底。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))

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
        runCurrent()
        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, controller.currentIndex.value)
        assertFalse(controller.buffering.value)
        assertEquals(40_000L, controller.progress.value.positionMillis)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(true, engine.startedPaused.last())
        assertEquals(40_000L, engine.startedPositions.last())
        // 手动切集失败：过渡期继续沿用屏幕上停住的旧帧，不铺黑底。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))

        engine.reportFileLoaded()
        assertTrue(controller.switching.value)

        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertFalse(blackLayer(controller))
    }

    @Test
    fun `cancelling a natural end switch goes black while the origin is restored`() = runTest {
        val controller = playingController()

        engine.reportPlaybackEnded()
        assertTrue(showsPicture(controller))

        assertTrue(controller.cancelSwitch())

        // 取消后不再沿用末帧：恢复原片期间按黑底等待它重新出帧。
        assertTrue(controller.restoring.value)
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))
    }

    @Test
    fun `a failed switch reports playback failure`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportPlaybackFailed()
        runCurrent()
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

        // 取消同样沿用屏幕上停住的旧帧，恢复原片期间不铺黑底。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))

        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        assertFalse(controller.switching.value)
        // 重配只说明视频输出重建了，画面可能还没上屏：屏上的旧帧不撤，等真正出帧再换。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))

        engine.reportFramePresented()
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(PlaybackState.Paused, controller.state.value)
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
    fun `a switch never settles on elapsed time alone`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        assertTrue(controller.switching.value)

        // 加载完成不是进展：没有首帧上屏也没有位置前进时，切集不按时长自行收尾。
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(0, engine.resumeCalls)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a switched target with no progress fails at ten seconds and restores the original`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        // 加载完成不是进展：旧画面还留在屏上，不铺黑底。
        assertFalse(blackLayer(controller))

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1)
        runCurrent()
        assertFalse(blackLayer(controller))
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        // 10 秒仍无进展：取消切换，回到原片原位置并保持暂停。
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(controller.restoring.value)
        assertEquals(1, failures.size)
        // 一直没有目标画面的证据：屏幕仍是原片画面，回退继续沿用，不铺黑底。
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(40_000L, engine.startedPositions.last())
    }

    @Test
    fun `a switched target that reconfigured video without a frame falls back to black`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(40_000, 600_000)
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        // 视频输出已经重配：屏幕不再是原片画面，只是还没有新帧上屏。
        engine.reportVideoReconfigured()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()

        // 10 秒仍无新帧：回退原片，屏上已没有旧画面，按黑底等待它重新出帧。
        assertEquals(0, controller.currentIndex.value)
        assertTrue(controller.restoring.value)
        assertEquals(1, failures.size)
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))
    }

    @Test
    fun `a paused switch settles on the ready signal and then waits for its frame`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        controller.pause()
        // 暂停态没有后续位置事件：收到画面信号即按暂停收尾，直接露新集首帧。
        engine.reportVideoReconfigured()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(showsPicture(controller))

        // 状态已就绪但帧没上屏：等新帧窗口照常走，暂停不挂起它。
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS - 1)
        runCurrent()
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(0, controller.currentIndex.value)
    }

    @Test
    fun `late load events do not extend the switch guard`() = runTest {
        val controller = playingController()
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        progress.holdFinds()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(controller.switching.value)

        // 续播查询与 seek 在 4 秒后才完成；加载事件都不算进展，也不给看护续期。
        progress.releaseFinds()
        runCurrent()
        engine.reportLoadActivity()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 4_000)
        runCurrent()

        assertEquals(1, failures.size)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `surface detach pauses the switch guard and reattach continues it`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(controller.switching.value)

        // 分离期间不计时：已经跑的 4 秒保留，剩下的等回前台再走。
        controller.setRenderSurfaceAvailable(false)
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS * 2)
        runCurrent()
        assertTrue(failures.isEmpty())
        assertTrue(controller.switching.value)

        controller.setRenderSurfaceAvailable(true)
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 4_000 - 1)
        runCurrent()
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(0, controller.currentIndex.value)
    }

    @Test
    fun `stopping or starting a new load cancels the switch guard`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(controller.switching.value)

        // 停止：旧的切集计时不能把已结束的会话报成失败。
        controller.stop()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertTrue(failures.isEmpty())
        assertEquals(PlaybackState.Idle, controller.state.value)

        // 停止后重新打开：上一轮计时同样不能残留。
        controller.playAt(1)
        engine.reportMediaLoaded()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS * 2)
        runCurrent()
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a presented frame ends the switch and cancels its guard`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        engine.reportFramePresented()
        assertFalse(controller.switching.value)

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS * 2)
        runCurrent()
        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `cancelling a switch cancels its guard`() = runTest {
        val controller = playingController()

        controller.next()
        engine.reportFileLoaded()
        advanceTimeBy(4_000)
        runCurrent()
        controller.cancelSwitch()

        // 被取消的切集不再重发或判失败；恢复原片走自己的同一条看护。
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(controller.restoring.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)

        // 恢复原片的加载一直没回来：它自己的 10 秒到点判失败，落到既有失败收尾。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `retargeting a switch restarts its guard`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")

        controller.next()
        advanceTimeBy(4_000)
        runCurrent()
        controller.next()

        // 新目标重新起算：旧目标剩下的 1 秒不会触发重发。
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
        assertEquals(2, controller.currentIndex.value)
        assertEquals(0, engine.discardInFlightCalls)
    }

    @Test
    fun `surface detach while waiting for the frame settles on file load`() = runTest {
        val controller = playingController()

        controller.next()
        engine.reportFileLoaded()
        advanceTimeBy(1_000)
        runCurrent()
        controller.setRenderSurfaceAvailable(false)

        // 退后台后没有可渲染画面，以文件加载完成作为就绪。
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 回前台只恢复画面，不重新进入加载。
        controller.setRenderSurfaceAvailable(true)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS)
        runCurrent()
        assertFalse(controller.switching.value)
    }

    @Test
    fun `a switch waits for a frame after the resume lookup finishes`() = runTest {
        val controller = playingController()
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        progress.holdFinds()

        controller.next()
        engine.reportFileLoaded()
        advanceTimeBy(1_000)
        runCurrent()
        // 续播位置还没查到：不能收尾。
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)

        progress.releaseFinds()
        runCurrent()
        // 已发出续播 seek：等 seek 之后的画面信号。
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(controller.switching.value)

        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a paused target settles when the paused restart signal arrives`() = runTest {
        val controller = playingController()
        controller.next()
        controller.pause()
        engine.reportFileLoaded()

        // 暂停态没有计时收尾：要等到画面信号才按暂停收尾。
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(controller.switching.value)

        engine.reportPausedRestart()

        // 就绪即露新集首帧，不铺黑底。
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)
        assertTrue(showsPicture(controller))
    }

    @Test
    fun `the switch guard fails a switch whose resume lookup never finishes`() = runTest {
        val controller = playingController()
        progress.holdFinds()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(controller.switching.value)

        // 查询一直不完成，看护照样在 10 秒判失败。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 5_000)
        runCurrent()

        assertTrue(controller.switching.value)
        assertTrue(controller.restoring.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(1, failures.size)
    }

    @Test
    fun `a switch without a presented frame keeps the old picture and fails at ten seconds`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()

        // 没有首帧上屏也没有位置前进：旧画面继续沿用，到 10 秒才收尾。
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 1)
        runCurrent()
        assertTrue(controller.switching.value)
        assertFalse(blackLayer(controller))
        assertTrue(failures.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertTrue(controller.restoring.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(1, failures.size)
        // 视频输出已重配：回退按黑底等原片重新出帧。
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))
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
        engine.reportFirstFrameReady()

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
        engine.reportFirstFrameReady()

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

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
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
    fun `an initial load that never emits any event fails at the ten second guard`() = runTest {
        val controller = controller()
        val failures = collectFailures(controller)

        open(controller, "ep1.mkv")
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()

        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(1, failures.size)
        // 时间本身不触发重发：首次打开与切集一样只在读到失败时重发。
        assertEquals(1, engine.startedUrls.size)
    }

    @Test
    fun `a switch that never emits any event fails at the ten second guard`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()

        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals("root/ep1.mkv", engine.startedUrls.last())
        assertEquals(1, failures.size)
        // 10 秒判失败回退前丢弃一次在途加载。
        assertEquals(1, engine.discardInFlightCalls)
    }

    @Test
    fun `load activity does not extend the initial load guard`() = runTest {
        val controller = controller()

        open(controller, "ep1.mkv")
        advanceTimeBy(4_999)
        engine.reportLoadActivity()
        advanceTimeBy(1)
        runCurrent()
        // 加载事件不是进展：不产生重发，10 秒照样判失败。
        assertEquals(PlaybackState.Loading, controller.state.value)

        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS - 5_000)
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

        // 这条信号不对应任何 seek：它只是加载自身的结果，不能结束加载。
        engine.reportFirstFrameReady(fromSeek = false)
        assertTrue(controller.switching.value)
        assertEquals(PlaybackState.Loading, controller.state.value)

        // 续播 seek 执行后引擎会再次上报 PLAYBACK_RESTART，这一条才算就绪。
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `续播 seek 之后再有一跳 seek，就绪仍然发生`() = runTest {
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        val controller = playingController()

        controller.next()
        engine.reportFileLoaded()
        assertEquals(20_000L, engine.seekPositions.last())

        // 续播那一跳之后用户又跳了一跳：命令序号更大。
        controller.seekTo(30_000)
        assertEquals(30_000L, engine.pendingSeekMillis)

        // 两跳先后执行，ready 带回的是后一跳的序号：不早于续播 seek，也算就绪。
        engine.reportSeekExecuted()
        engine.reportFirstFrameReady()

        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
        // 后一跳是这一代加载的最终落点：就绪后进度停在用户目标上，没有丢。
        assertEquals(30_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `a real ready after the resume seek timed out still counts as ready`() = runTest {
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        val controller = playingController()
        val failures = collectFailures(controller)

        engine.reportPlaybackEnded()
        engine.reportFileLoaded()
        assertEquals(20_000L, engine.seekPositions.last())

        // 续播 seek 超时：引擎按执行完处理并回报，真正的 ready 之后才到。
        engine.reportSeekExecuted()
        assertTrue(controller.switching.value)

        // 真实 ready 带的是续播那一跳的序号：不能因为晚于超时就被判失败。
        engine.reportFirstFrameReady()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)

        engine.reportFramePresented()
        advanceTimeBy(PlaybackController.LOAD_FAIL_DELAY_MILLIS)
        runCurrent()
        assertTrue(failures.isEmpty())
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
        assertEquals(PlaybackState.Loading, controller.state.value)

        // 不对应续播 seek 的信号不算数。
        engine.reportFirstFrameReady(fromSeek = false)
        assertEquals(PlaybackState.Loading, controller.state.value)

        engine.reportFirstFrameReady()
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

        // 后台没有可渲染画面：文件加载完成即就绪。
        assertEquals(1, controller.currentIndex.value)
        assertFalse(controller.switching.value)
        assertFalse(controller.buffering.value)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 回前台重挂 Surface 不应重新进入加载；出帧后等新帧窗口随之结束。
        controller.setRenderSurfaceAvailable(true)
        runCurrent()
        engine.reportFramePresented()
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS)
        runCurrent()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `an initial load failure stops the engine and reopening starts cleanly`() = runTest {
        val controller = controller()
        val failures = collectFailures(controller)

        open(controller, "ep1.mkv")
        // 第一次读失败先重发一次，重发也失败才按首次打开失败收尾。
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(1, failures.size)
        assertEquals(1, engine.stopCalls)

        open(controller, "ep1.mkv")
        assertEquals(PlaybackState.Loading, controller.state.value)
        engine.reportMediaLoaded()
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(3, engine.startedUrls.size)
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
        runCurrent()
        engine.reportPlaybackFailed()
        assertEquals(1, failures.size)

        // 恢复原片的读失败同样先重发一次，重发也失败才结束会话，且不再重复提示。
        engine.reportPlaybackFailed()
        runCurrent()
        assertEquals(1, failures.size)

        engine.reportPlaybackFailed()

        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(1, failures.size)
        assertFalse(controller.switching.value)
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

    @Test
    fun `a late failure from a cancelled target is dropped`() = runTest {
        val controller = playingController()
        val failures = collectFailures(controller)

        controller.next()
        val cancelledToken = engine.currentLoadToken
        assertTrue(controller.cancelSwitch())
        val restoreToken = engine.currentLoadToken

        // 被取消目标的失败在取消之后才到：不得停掉恢复中的原片，也不得提示失败。
        engine.reportPlaybackFailed("旧目标失败", loadToken = cancelledToken)

        assertEquals(0, failures.size)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(controller.restoring.value)
        assertEquals(restoreToken, engine.currentLoadToken)
        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv", "root/ep1.mkv"), engine.startedUrls)

        // 当前恢复加载的读失败先重发一次。
        engine.reportPlaybackFailed("恢复失败", loadToken = restoreToken)
        runCurrent()
        assertEquals(0, failures.size)

        // 重发也失败：仍然处理，结束时只提示一次。
        engine.reportPlaybackFailed("恢复失败")
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `a late failure from a retargeted target is dropped`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        val failures = collectFailures(controller)

        controller.next()
        val abandonedToken = engine.currentLoadToken
        controller.next()
        val targetToken = engine.currentLoadToken

        // 已被重定向掉的目标失败：当前切集目标不变，不触发恢复或提示。
        engine.reportPlaybackFailed("旧目标失败", loadToken = abandonedToken)

        assertEquals(0, failures.size)
        assertEquals(2, controller.currentIndex.value)
        assertTrue(controller.switching.value)
        assertEquals(targetToken, engine.currentLoadToken)
        assertEquals(
            listOf("root/ep1.mkv", "root/ep2.mkv", "root/ep3.mkv"),
            engine.startedUrls,
        )

        // 当前目标的读失败先重发一次，重发也失败才回退原片。
        engine.reportPlaybackFailed("目标失败", loadToken = targetToken)
        runCurrent()
        engine.reportPlaybackFailed("目标失败")
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a resume with no echo does not hide real load activity`() {
        val gate = ResumeActivityGate()

        gate.onResume(7)

        // 0 次回声：真实加载事件仍按活动处理，刷新无帧窗口。
        assertFalse(gate.shouldIgnore(LoadActivity.Seek, 7))
        assertFalse(gate.shouldIgnore(LoadActivity.FileLoaded, 7))
        assertFalse(gate.shouldIgnore(LoadActivity.VideoReconfig, 7))
    }

    @Test
    fun `切集期间按一次播放键记下的暂停意图在就绪后生效`() = runTest {
        val sink = MemoryDiagnosticsSink()
        val controller = playingControllerWithDiagnostics(sink)
        val resumeCallsBefore = engine.resumeCalls

        controller.next()
        assertEquals(PlaybackState.Loading, controller.state.value)
        controller.togglePlayPause()

        // 这一击在切集期就被记下：切集加载中的播放/暂停不落到「未就绪，不动作」。
        assertTrue(sink.lines.any { it.contains("切集期间播放键: 排队暂停") })

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        // 就绪后可见状态就是按下的意图。
        assertFalse(controller.switching.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(resumeCallsBefore, engine.resumeCalls)
    }

    @Test
    fun `切集期间按下的暂停在重新前移目标后仍然生效`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")

        controller.next()
        controller.togglePlayPause()
        controller.next()

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(2, controller.currentIndex.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)
    }

    @Test
    fun `切集期间连点两次下一集目标前移两格`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")

        controller.next()
        controller.next()

        // 每一击都前移目标：起始第 0 集，两击后目标是第 2 集，最终只等这一集就绪。
        assertEquals(2, controller.currentIndex.value)
        assertEquals("root/ep3.mkv", engine.startedUrls.last())
        assertTrue(controller.switching.value)

        // 被跳过的中间集迟到的就绪不能结束切集。
        engine.reportFirstFrameReady(targetUrl = "root/ep2.mkv")
        assertTrue(controller.switching.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(2, controller.currentIndex.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a single resume echo is ignored but real load activity still refreshes`() {
        val gate = ResumeActivityGate()

        gate.onResume(7)
        assertTrue(gate.shouldIgnore(LoadActivity.PlaybackRestart, 7))
        // 真实 SEEK 结束回声期，后续 PLAYBACK_RESTART 按真实事件处理。
        assertFalse(gate.shouldIgnore(LoadActivity.Seek, 7))
        assertFalse(gate.shouldIgnore(LoadActivity.PlaybackRestart, 7))
    }

    @Test
    fun `multiple resume echoes do not repeatedly refill the window`() {
        val gate = ResumeActivityGate()

        gate.onResume(7)
        repeat(3) {
            assertTrue(gate.shouldIgnore(LoadActivity.PlaybackRestart, 7))
        }
        // 回声次数有上限：超出后按真实事件刷新窗口，不会无限忽略。
        assertFalse(gate.shouldIgnore(LoadActivity.PlaybackRestart, 7))
        // 真实 VIDEO_RECONFIG 仍然刷新。
        assertFalse(gate.shouldIgnore(LoadActivity.VideoReconfig, 7))

        // 不同加载令牌的 PLAYBACK_RESTART 不是这次 resume 的回声。
        gate.onResume(8)
        assertFalse(gate.shouldIgnore(LoadActivity.PlaybackRestart, 7))
    }

    @Test
    fun `an old-generation event does not end the resume echo period`() {
        val gate = ResumeActivityGate()

        gate.onResume(7)
        // 旧代际事件不清除回声期。
        assertFalse(gate.shouldIgnore(LoadActivity.Seek, 6))
        assertTrue(gate.shouldIgnore(LoadActivity.PlaybackRestart, 7))

        // 同令牌真实事件才结束回声期。
        assertFalse(gate.shouldIgnore(LoadActivity.FileLoaded, 7))
        assertFalse(gate.shouldIgnore(LoadActivity.PlaybackRestart, 7))
    }

    @Test
    fun `a paused switch shows the new frame and ratio on ready`() = runTest {
        val controller = playingController()
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportFramePresented()
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoSizeChanged(1080, 1920)

        // 屏上仍是原片画面：比例不跟着新文件换。
        assertTrue(showsPicture(controller))
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)

        controller.pause()
        engine.reportVideoReconfigured()

        // 暂停态就绪：同一次派生里撤掉旧帧、换成新文件比例，直接露新集首帧，不铺黑底。
        assertFalse(controller.switching.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(1080.0 / 1920.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)
    }

    @Test
    fun `the first load stays black until the first frame is ready`() = runTest {
        val controller = controller()
        open(controller, "ep1.mkv")

        // 还没有任何画面信号：按黑底等首帧。
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))

        engine.reportMediaLoaded()

        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
    }

    @Test
    fun `next after a background round trip during a restore stays black`() = runTest {
        val controller = playingController("ep1.mkv", "ep2.mkv", "ep3.mkv")
        engine.reportPositionChanged(40_000, 600_000)
        controller.next()
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()
        assertTrue(controller.restoring.value)
        assertTrue(showsPicture(controller))

        // 退后台再回来：Surface 重建，屏上原有的画面不再可信。
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
        assertFalse(showsPicture(controller))

        controller.next()

        // 没有原片画面可沿用：这一段切集按黑底等目标首帧，不误判为有画面。
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))
    }

    @Test
    fun `a presented frame withdraws the old frame and takes the new ratio`() = runTest {
        val controller = playingController()
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportFramePresented()

        controller.next()
        engine.reportFileLoaded()
        engine.reportVideoSizeChanged(1080, 1920)
        // 还没有真正的出帧：屏上是原片画面与原片比例。
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)

        // 播放位置前进：新帧真的上屏，撤旧帧并换成新文件比例；出帧即完成切集。
        engine.reportFramePresented()

        assertFalse(controller.switching.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(1080.0 / 1920.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)
    }

    @Test
    fun `a paused natural end switch shows the target frame instead of black`() = runTest {
        val controller = playingController()
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportFramePresented()
        engine.reportPositionChanged(600_000, 600_000)

        // 片尾自然播完触发切集：切集起点被标记为片尾结束。
        engine.reportPlaybackEnded()
        engine.reportFileLoaded()
        engine.reportVideoSizeChanged(1080, 1920)
        engine.reportVideoReconfigured()
        assertEquals(1, engine.resumeCalls)

        // 就绪后暂停：退后台与音频输出中断走同一条收尾路径。
        controller.pauseForBackground()

        // 片尾标记不能把暂停态刚露出的新集首帧盖成黑底。
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(showsPicture(controller))
        assertFalse(blackLayer(controller))
        assertEquals(1080.0 / 1920.0, controller.surfacePresentation.value.aspectRatio!!, RATIO_TOLERANCE)
    }

    @Test
    fun `a paused natural end switch that never gets a frame restores black`() = runTest {
        val controller = playingController()
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportFramePresented()
        engine.reportPositionChanged(600_000, 600_000)
        val failures = collectFailures(controller)

        engine.reportPlaybackEnded()
        engine.reportFileLoaded()
        engine.reportVideoReconfigured()
        controller.pauseForBackground()
        assertTrue(showsPicture(controller))

        // 继续播放后仍等不到帧：60 秒兜底回退原片，片尾恢复不再沿用旧帧。
        controller.resume()
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS)
        runCurrent()

        assertEquals(0, controller.currentIndex.value)
        assertTrue(controller.restoring.value)
        assertEquals(1, failures.size)
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))

        // 片尾恢复就绪后仍保持黑底：原片重新出帧也不露，等用户操作。
        engine.reportFileLoaded()
        engine.reportFramePresented()
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertFalse(showsPicture(controller))
        assertTrue(blackLayer(controller))
    }

    private companion object {
        const val RATIO_TOLERANCE = 0.0001
    }

    /** 控制层派生的画面判据：屏幕上有没有可显示的画面。 */
    private fun showsPicture(controller: PlaybackController): Boolean =
        controller.surfacePresentation.value.showsPicture

    /** 界面按同一判据决定是否铺黑底；切集时屏上旧帧已有比例，不再盖黑。 */
    private fun blackLayer(controller: PlaybackController): Boolean =
        showPlayerBlackLayer(
            showsPicture(controller),
            controller.surfacePresentation.value.aspectRatio,
            orientationSettled = true,
            orientationPending = false,
        )

    private fun dispatch(controller: PlaybackController, action: MediaSessionAction) {
        dispatchMediaAction(
            action,
            controller.state.value,
            controller.switching.value,
            controller.restoring.value,
        ) { it.applyTo(controller) }
    }

    /** 控制层用即时调度，对齐生产的主线程 immediate。 */
    private fun TestScope.controller(): PlaybackController =
        PlaybackController(
            engine,
            progress,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            nowMillis = { testScheduler.currentTime },
        )

    /** 带诊断的控制层：用来断言被拒绝的点击留下了意图记录。 */
    private fun TestScope.controller(diagnostics: Diagnostics): PlaybackController =
        PlaybackController(
            engine,
            progress,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            diagnostics = diagnostics,
            nowMillis = { testScheduler.currentTime },
        )

    private fun TestScope.playingControllerWithDiagnostics(
        sink: MemoryDiagnosticsSink,
        vararg names: String = arrayOf("ep1.mkv", "ep2.mkv"),
    ): PlaybackController {
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher(testScheduler)).apply { enabled = true }
        val controller = controller(diagnostics)
        open(controller, *names)
        engine.reportMediaLoaded()
        return controller
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
