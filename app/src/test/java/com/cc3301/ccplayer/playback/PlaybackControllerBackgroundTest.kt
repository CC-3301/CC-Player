package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerBackgroundTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `暂停态退到后台缓存当前帧`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = pausedController()

        controller.captureBackgroundFrame()

        assertSame(frame, controller.backgroundRestoreFrame.value)
        assertEquals(1, engine.grabFrameCalls)
    }

    @Test
    fun `同一后台周期不重复抓帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = pausedController()

        controller.captureBackgroundFrame()
        controller.captureBackgroundFrame()

        assertEquals(1, engine.grabFrameCalls)
    }

    @Test
    fun `播放中退到后台不截帧`() = runTest {
        val controller = playingController()

        controller.captureBackgroundFrame()

        assertEquals(0, engine.grabFrameCalls)
        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `没有会话时不截帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())

        controller().captureBackgroundFrame()

        assertEquals(0, engine.grabFrameCalls)
    }

    @Test
    fun `加载中不截帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = controller()
        val files = listOf(MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"))
        controller.openPlaylist(files, files.first())
        controller.pause()

        controller.captureBackgroundFrame()

        assertEquals(0, engine.grabFrameCalls)
    }

    @Test
    fun `纯音频抓不到帧时不缓存`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        val controller = pausedController()

        controller.captureBackgroundFrame()

        assertEquals(1, engine.grabFrameCalls)
        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `片尾黑底不缓存末帧`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        controller.next()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertTrue(controller.holdBlack.value)
        val grabsBefore = engine.grabFrameCalls

        controller.captureBackgroundFrame()

        assertEquals(grabsBefore, engine.grabFrameCalls)
        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `回前台播放核心出帧后撤掉缓存帧`() = runTest {
        val controller = capturedController()

        engine.reportSurfaceReattached()
        engine.reportFramePresented()

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `迟到或未重新挂载的出帧通知不清缓存帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)

        engine.reportFramePresented()

        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `没有可用 Surface 时出帧通知不清缓存帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()

        engine.reportFramePresented()

        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `暂停态重配后抓帧确认才撤掉缓存帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        engine.frameToGrab = null
        reattach(controller)

        engine.reportSurfaceReconfigured()

        // 重配早于首帧上屏时保留缓存帧。
        assertSame(frame, controller.backgroundRestoreFrame.value)

        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS)
        runCurrent()

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `暂停态抓帧确认失败时保留缓存帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        engine.frameToGrab = null
        reattach(controller)

        engine.reportSurfaceReconfigured()
        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS * 3)
        runCurrent()

        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `缓存帧不会在起播新会话后残留`() = runTest {
        val controller = capturedController()

        controller.playAt(controller.currentIndex.value + 1)

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `关闭后台播放时退到后台先暂停再缓存当前帧`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)
        policy.setBackgroundPlaybackEnabled(false)

        policy.setAppInForeground(false)
        controller.captureBackgroundFrame()

        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `开启后台播放且正在播放时退到后台不缓存`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)

        policy.setAppInForeground(false)
        controller.captureBackgroundFrame()

        assertEquals(0, engine.grabFrameCalls)
        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `暂停态且后台播放开启时退到后台缓存当前帧`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = pausedController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)

        policy.setAppInForeground(false)
        controller.captureBackgroundFrame()

        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `暂停态且后台播放关闭时退到后台缓存当前帧`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = pausedController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)
        policy.setBackgroundPlaybackEnabled(false)

        policy.setAppInForeground(false)
        controller.captureBackgroundFrame()

        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `片尾黑底在后台暂停后仍然保持`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        controller.next()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertTrue(controller.holdBlack.value)

        controller.pauseForBackground()

        assertTrue(controller.holdBlack.value)
    }

    private fun TestScope.controller(): PlaybackController =
        PlaybackController(
            engine,
            progress,
            backgroundScope,
            frameProbeDispatcher = UnconfinedTestDispatcher(testScheduler),
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

    private suspend fun TestScope.capturedController(
        frame: Bitmap = testBitmap(),
    ): PlaybackController {
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = pausedController()
        controller.captureBackgroundFrame()
        assertSame(frame, controller.backgroundRestoreFrame.value)
        return controller
    }

    private fun reattach(controller: PlaybackController) {
        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
    }

    /**
     * 测试只需要一个非空帧身份；android.jar 的 Bitmap 构造器在单测里是桩，
     * 沿用现有测试的 Unsafe 分配方式。
     */
    private fun testBitmap(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Bitmap::class.java) as Bitmap
    }
}
