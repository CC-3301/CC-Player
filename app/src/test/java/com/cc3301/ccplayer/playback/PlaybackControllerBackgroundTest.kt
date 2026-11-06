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
    fun `暂停态重挂刷新后直接显示暂停帧`() = runTest {
        val controller = pausedController()
        engine.reportPositionChanged(42_000, 600_000)

        reattach(controller)
        // 回执按引擎事件链异步到达：刷新刚下发时画面还没撤黑。
        assertFalse(controller.surfacePresentation.value.showsPicture)
        runCurrent()

        // 暂停态发一次位置中性刷新，播放核心补报帧上屏后撤黑。
        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(1, engine.refreshFrameCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(42_000L, controller.progress.value.positionMillis)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)

        // 回执已到：不再等 3 秒翻转，也不显示缓冲指示。
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()
        assertEquals(0, engine.flipVideoOutputCalls)
        assertFalse(controller.buffering.value)
    }

    @Test
    fun `刷新回执没来才在 3 秒后翻转视频输出`() = runTest {
        engine.refreshEmitsFrame = false
        val controller = pausedController()

        reattach(controller)
        runCurrent()
        // 暂停态先请求播放核心重解当前帧，回执没来才走翻转兜底。
        assertEquals(1, engine.refreshFrameCalls)
        assertFalse(controller.surfacePresentation.value.showsPicture)
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
    fun `翻转兜底后下一次重挂重新请求刷新`() = runTest {
        engine.dropRefreshReceipt = true
        val controller = pausedController()

        reattach(controller)
        runCurrent()
        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertEquals(1, engine.refreshFrameCalls)

        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS)
        runCurrent()

        // 回执没来：只做一次翻转兜底，画面保持黑底。
        assertEquals(1, engine.flipVideoOutputCalls)
        assertFalse(controller.surfacePresentation.value.showsPicture)

        // 下一次重挂重新请求一次精确刷新；刷新序号归属由引擎单测覆盖。
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
        runCurrent()
        assertEquals(2, engine.refreshFrameCalls)
    }

    @Test
    fun `暂停态重挂重复回调只刷新一次`() = runTest {
        val controller = pausedController()
        engine.reportPositionChanged(42_000, 600_000)

        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
        controller.setRenderSurfaceAvailable(true)
        runCurrent()

        assertEquals(1, engine.refreshFrameCalls)
        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertEquals(42_000L, controller.progress.value.positionMillis)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertTrue(engine.seekPositions.isEmpty())

        // 刷新不算加载：不显示缓冲指示，也不改变进度；回执到了就不再翻转。
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()
        assertFalse(controller.buffering.value)
        assertEquals(0, engine.flipVideoOutputCalls)
        assertEquals(42_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `片尾暂停重挂不请求精确刷新`() = runTest {
        val controller = playingController()
        controller.startSleepTimer(SleepTimerOption.EndOfEpisode)
        engine.reportPositionChanged(600_000, 600_000)
        engine.reportPlaybackEnded()

        reattach(controller)
        runCurrent()

        // 片尾不再 seek，避免重新触发 eof 与连播判定。
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.refreshFrameCalls)
        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `播放态重挂不请求精确刷新`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)

        reattach(controller)
        runCurrent()

        assertEquals(0, engine.refreshFrameCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(30_000L, controller.progress.value.positionMillis)
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
    fun `分离期间不计时，回前台只走剩下的等新帧窗口`() = runTest {
        val controller = playingController()
        controller.next()
        engine.reportFileLoaded()
        // 暂停态收到画面信号即收尾：切集已就绪，只等新帧上屏。
        controller.pause()
        engine.reportVideoReconfigured()
        assertFalse(controller.switching.value)
        val startedLoads = engine.startedUrls.size

        advanceTimeBy(30_000)
        controller.setRenderSurfaceAvailable(false)
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS * 2)
        runCurrent()

        // 分离期间窗口不计时：后台不会把它判成失败。
        assertEquals(startedLoads, engine.startedUrls.size)
        assertFalse(controller.switching.value)

        // 回前台只接着剩下的 30 秒走，不重新给满窗口；切集等帧期间不插精确刷新。
        controller.setRenderSurfaceAvailable(true)
        assertEquals(0, engine.refreshFrameCalls)
        advanceTimeBy(PlaybackController.NO_FRAME_WINDOW_MILLIS - 30_000 - 1)
        runCurrent()
        assertEquals(startedLoads, engine.startedUrls.size)

        advanceTimeBy(1)
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

        // 切集自己等新帧，重挂不叠加恢复等待，也不插精确刷新。
        assertEquals(0, engine.flipVideoOutputCalls)
        assertEquals(0, engine.refreshFrameCalls)
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
        // 第一次读失败先重发一次，重发也失败才回退原片。
        engine.reportPlaybackFailed()
        runCurrent()
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

        // 暂停态回前台只发一次位置中性刷新，不跳转、不自动继续。
        assertEquals(1, engine.refreshFrameCalls)
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
        // 第一次读失败先重发一次，重发也失败才回退原片。
        engine.reportPlaybackFailed()
        runCurrent()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        // 片尾自动恢复：黑底保持到用户操作。
        assertFalse(controller.surfacePresentation.value.showsPicture)

        controller.pauseForBackground()

        assertFalse(controller.surfacePresentation.value.showsPicture)
    }

    @Test
    fun `播放态回前台撤掉旧画面并等新帧`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)
        assertTrue(controller.surfacePresentation.value.showsPicture)

        controller.setAppInForeground(false)
        controller.setAppInForeground(true)

        // 旧画面不保留：纯黑等下一个新帧，不 seek。
        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertEquals(1, engine.awaitFramePresentationCalls)
        assertTrue(engine.seekPositions.isEmpty())

        engine.reportFramePresented()
        assertTrue(controller.surfacePresentation.value.showsPicture)
    }

    @Test
    fun `暂停态回前台保留最后一帧`() = runTest {
        val controller = pausedController()
        engine.reportPositionChanged(42_000, 600_000)

        controller.setAppInForeground(false)
        controller.setAppInForeground(true)

        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertEquals(0, engine.awaitFramePresentationCalls)
        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `后台切集不继承屏上旧画面`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)
        assertTrue(controller.surfacePresentation.value.showsPicture)

        controller.setAppInForeground(false)
        controller.next()

        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertEquals(0, engine.awaitFramePresentationCalls)

        // 回前台播放态同样不保留旧画面。
        controller.setAppInForeground(true)
        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `播放态回前台撤帧后 3 秒无新帧只翻转一次`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)
        controller.setAppInForeground(false)
        controller.setAppInForeground(true)

        assertFalse(controller.surfacePresentation.value.showsPicture)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS - 1)
        runCurrent()
        assertEquals(0, engine.flipVideoOutputCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.flipVideoOutputCalls)
        // 等待有上限：翻转后不再重复等，也不再翻转。
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 3)
        runCurrent()
        assertEquals(1, engine.flipVideoOutputCalls)
        assertFalse(controller.surfacePresentation.value.showsPicture)
    }

    @Test
    fun `回前台暂停态每次恢复都允许一次精确刷新`() = runTest {
        engine.refreshEmitsFrame = false
        val controller = pausedController()
        // 第一次恢复：Surface 重挂，刷新一次，回执没来走 3 秒翻转。
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)
        runCurrent()
        assertEquals(1, engine.refreshFrameCalls)

        // 同一次恢复里回前台入口不重复刷新。
        controller.setAppInForeground(false)
        controller.setAppInForeground(true)
        runCurrent()
        assertEquals(1, engine.refreshFrameCalls)

        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS)
        runCurrent()
        assertEquals(1, engine.flipVideoOutputCalls)

        // 下一次恢复仍允许一次刷新。
        controller.setAppInForeground(false)
        controller.setAppInForeground(true)
        runCurrent()
        assertEquals(2, engine.refreshFrameCalls)

        // 让本次恢复的兜底计时走完，不把未完成的等待留到测试结束。
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS)
        runCurrent()
    }

    @Test
    fun `前台切集进行中回前台撤帧后黑屏不显示缓冲提示`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)
        controller.next()

        controller.setAppInForeground(false)
        controller.setAppInForeground(true)
        advanceTimeBy(PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS)
        runCurrent()

        // 切集缓冲灯照常亮起，但屏上旧画面已撤：黑屏期间不显示提示。
        assertTrue(controller.buffering.value)
        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertTrue(controller.surfacePresentation.value.quietBlack)
    }

    @Test
    fun `首次加载中回前台不留下黑屏无提示标记`() = runTest {
        val controller = controller()
        val files = listOf(MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"))
        controller.openPlaylist(files, files.first())

        controller.setAppInForeground(false)
        controller.setAppInForeground(true)

        assertFalse(controller.surfacePresentation.value.quietBlack)
    }

    @Test
    fun `前台切集继续沿用旧画面`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)

        controller.next()

        assertTrue(controller.surfacePresentation.value.showsPicture)
    }

    private fun TestScope.controller(): PlaybackController {
        engine.callbackScope = this
        return PlaybackController(
            engine,
            progress,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            nowMillis = { testScheduler.currentTime },
        )
    }

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
