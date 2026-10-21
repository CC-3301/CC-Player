package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerBackgroundTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `暂停态退到后台缓存当前帧`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = playingController()
        controller.pause()
        engine.reportPauseChanged(true)

        controller.captureBackgroundFrame()

        assertEquals(frame, controller.backgroundRestoreFrame.value)
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
    fun `回前台播放核心出帧后撤掉缓存帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = playingController()
        controller.pause()
        engine.reportPauseChanged(true)
        controller.captureBackgroundFrame()

        engine.reportFramePresented()

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `缓存帧不会在起播新会话后残留`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = playingController()
        controller.pause()
        engine.reportPauseChanged(true)
        controller.captureBackgroundFrame()

        controller.playAt(controller.currentIndex.value + 1)

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `关闭后台播放时退到后台先暂停再缓存当前帧`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pause)
        policy.setBackgroundPlaybackEnabled(false)

        policy.setAppInForeground(false)
        controller.captureBackgroundFrame()

        assertEquals(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `开启后台播放且正在播放时退到后台不缓存`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pause)

        policy.setAppInForeground(false)
        controller.captureBackgroundFrame()

        assertEquals(0, engine.grabFrameCalls)
        assertNull(controller.backgroundRestoreFrame.value)
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
