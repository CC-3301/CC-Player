package com.cc3301.ccplayer.playback

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerBackgroundTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `暂停态回前台不显示任何画面`() = runTest {
        val controller = pausedController()
        engine.reportPositionChanged(42_000, 600_000)

        reattach(controller)
        runCurrent()

        // 退后台不拆画面；Surface 被回收后重建只铺黑底，没有画面可显示，也不 seek。
        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertNull(controller.surfacePresentation.value.aspectRatio)
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(42_000L, controller.progress.value.positionMillis)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `挂载后 3 秒仍无新帧只翻转一次视频输出`() = runTest {
        val controller = pausedController()

        reattach(controller)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS - 1)
        runCurrent()
        assertEquals(0, engine.flipVideoOutputCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.flipVideoOutputCalls)
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(PlaybackState.Paused, controller.state.value)

        // 只翻转一次：之后不再翻转、不 seek。
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 3)
        runCurrent()
        assertEquals(1, engine.flipVideoOutputCalls)
        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `3 秒内收到新帧就不再翻转视频输出`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)

        reattach(controller)
        engine.reportPositionChanged(31_000, 600_000)
        engine.reportFramePresented()
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()

        assertEquals(0, engine.flipVideoOutputCalls)
        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(controller.surfacePresentation.value.showsPicture)
    }

    @Test
    fun `回前台不把已耗尽的等新帧窗口直接判失败`() = runTest {
        val controller = playingController()
        controller.next()
        engine.reportFileLoaded()
        // 2 秒兜底收尾：切集已就绪，只等新帧上屏，无帧窗口从此前的加载事件起算。
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS + 1)
        runCurrent()
        val startedLoads = engine.startedUrls.size

        // 窗口走到截止时刻时退到后台：挂起时剩余已为 0。
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS - PlaybackController.FRAME_FALLBACK_MILLIS - 1)
        controller.setRenderSurfaceAvailable(false)
        runCurrent()
        controller.setRenderSurfaceAvailable(true)
        runCurrent()

        // 回前台重新给一个完整窗口：不立刻回退原片，也不压成暂停。
        assertEquals(startedLoads, engine.startedUrls.size)
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 新帧上屏后照常收尾，之后不再触发失败。
        engine.reportPositionChanged(1_000, 600_000)
        engine.reportFramePresented()
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS * 2)
        runCurrent()
        assertEquals(startedLoads, engine.startedUrls.size)
    }

    @Test
    fun `窗口耗尽后反复回前台不会无限续期`() = runTest {
        val controller = playingController()
        controller.next()
        engine.reportFileLoaded()
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS + 1)
        runCurrent()
        val startedLoads = engine.startedUrls.size

        // 第一次回前台：窗口已耗尽，续一个完整窗口。
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS - PlaybackController.FRAME_FALLBACK_MILLIS - 1)
        controller.setRenderSurfaceAvailable(false)
        runCurrent()
        controller.setRenderSurfaceAvailable(true)
        runCurrent()
        assertEquals(startedLoads, engine.startedUrls.size)

        // 第二个窗口也耗尽：再次回前台不再续期，按切集失败回到原片。
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS)
        controller.setRenderSurfaceAvailable(false)
        runCurrent()
        controller.setRenderSurfaceAvailable(true)
        runCurrent()

        assertEquals(startedLoads + 1, engine.startedUrls.size)
    }

    @Test
    fun `Surface 分离时取消恢复等待`() = runTest {
        val controller = pausedController()

        reattach(controller)
        controller.setRenderSurfaceAvailable(false)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()

        assertEquals(0, engine.flipVideoOutputCalls)
    }

    @Test
    fun `切集进行中重挂不触发 3 秒翻转`() = runTest {
        val controller = playingController()

        controller.next()
        assertEquals(PlaybackState.Loading, controller.state.value)

        reattach(controller)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()

        // 切集自己等新帧，重挂不叠加恢复等待。
        assertEquals(0, engine.flipVideoOutputCalls)
    }

    @Test
    fun `首次加载中重挂不触发 3 秒翻转`() = runTest {
        val controller = controller()
        val files = listOf(MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"))
        controller.openPlaylist(files, files.first())

        reattach(controller)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()

        assertEquals(0, engine.flipVideoOutputCalls)
    }

    @Test
    fun `片尾黑底重挂不等待也不翻转`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        // 片尾自动恢复：黑底保持到用户操作。
        assertFalse(controller.surfacePresentation.value.showsPicture)

        reattach(controller)
        runCurrent()
        // 片尾黑底没有画面可露：不进入恢复等待，也不铺任何画面。
        assertFalse(controller.surfacePresentation.value.showsPicture)

        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()
        assertEquals(0, engine.flipVideoOutputCalls)
    }

    @Test
    fun `播放态回前台不 seek`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)

        reattach(controller)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 3)
        runCurrent()

        // 恢复完全不 seek：画面由播放核心重建输出后自行出帧。
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(30_000L, controller.progress.value.positionMillis)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `播放态回前台画面自然输出就不翻转`() = runTest {
        val controller = playingController()

        reattach(controller)
        engine.reportFramePresented()
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 3)
        runCurrent()

        assertEquals(0, engine.flipVideoOutputCalls)
        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `后台播放开启时回前台继续播放且不重新加载或跳位置`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)

        // 退后台：播放继续，画面不拆。
        policy.setAppInForeground(false)
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
        policy.setAppInForeground(true)

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(30_000L, controller.progress.value.positionMillis)

        // 回前台走重挂后的出帧路径：位置前进过一次，不再 seek、不翻转。
        engine.reportPositionChanged(31_000, 600_000)
        engine.reportFramePresented()
        assertTrue(controller.surfacePresentation.value.showsPicture)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(0, engine.flipVideoOutputCalls)
    }

    @Test
    fun `后台播放关闭时回前台保持暂停且不重新加载`() = runTest {
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)
        policy.setBackgroundPlaybackEnabled(false)

        // 退后台：立即暂停，画面不拆。
        policy.setAppInForeground(false)
        engine.reportPauseChanged(true)
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
        policy.setAppInForeground(true)

        // 暂停态回前台不 seek，也不自动继续。
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `片尾黑底在后台暂停后仍然保持`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        engine.reportPlaybackEnded()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        // 片尾自动恢复：黑底保持到用户操作。
        assertFalse(controller.surfacePresentation.value.showsPicture)

        controller.pauseForBackground()

        assertFalse(controller.surfacePresentation.value.showsPicture)
    }

    private fun TestScope.controller(): PlaybackController =
        PlaybackController(
            engine,
            progress,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            nowMillis = { testScheduler.currentTime },
        )

    private fun TestScope.playingController(): PlaybackController {
        val controller = controller()
        val files = listOf(
            MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"),
            MediaFile(name = "ep2.mkv", location = "root/ep2.mkv"),
        )
        controller.openPlaylist(files, files.first())
        engine.reportMediaLoaded()
        return controller
    }

    private fun TestScope.pausedController(): PlaybackController {
        val controller = playingController()
        controller.pause()
        engine.reportPauseChanged(true)
        return controller
    }

    private fun reattach(controller: PlaybackController) {
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
    }
}
