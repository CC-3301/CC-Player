package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import android.view.Surface
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
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

        capture(controller)

        assertSame(frame, controller.backgroundRestoreFrame.value)
        assertEquals(1, engine.grabFrameCalls)
    }

    @Test
    fun `同一后台周期不重复抓帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = pausedController()

        capture(controller)
        capture(controller)

        assertEquals(1, engine.grabFrameCalls)
    }

    @Test
    fun `播放中退到后台不截帧`() = runTest {
        val controller = playingController()

        capture(controller)

        assertEquals(0, engine.grabFrameCalls)
        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `没有会话时不截帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())

        capture(controller())

        assertEquals(0, engine.grabFrameCalls)
    }

    @Test
    fun `加载中不截帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = controller()
        val files = listOf(MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"))
        controller.openPlaylist(files, files.first())
        controller.pause()

        capture(controller)

        assertEquals(0, engine.grabFrameCalls)
    }

    @Test
    fun `纯音频抓不到帧时不缓存`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(bitmap = null)
        val controller = pausedController()

        capture(controller)

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

        capture(controller)

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
    fun `后台播放开启时回前台继续播放且不重新加载或跳位置`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)

        // 退后台：播放继续，画面停止渲染。
        policy.setAppInForeground(false)
        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
        policy.setAppInForeground(true)

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(30_000L, controller.progress.value.positionMillis)

        // 回前台重新出帧后不需要再显示缓存帧。
        engine.reportFramePresented()
        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `后台播放关闭时回前台显示暂停帧并保持暂停`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)
        policy.setBackgroundPlaybackEnabled(false)

        // 退后台：立即暂停，再抓一帧留给回前台。
        policy.setAppInForeground(false)
        engine.reportPauseChanged(true)
        capture(controller)
        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
        policy.setAppInForeground(true)

        assertSame(frame, controller.backgroundRestoreFrame.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `Surface 重挂后没有新帧时只刷新一次且位置不变`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(12_000, 600_000)
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)

        advanceTimeBy(PlaybackController.SURFACE_REFRESH_DELAY_MILLIS)
        runCurrent()

        assertEquals(1, engine.refreshSurfaceCalls)
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertEquals(12_000L, controller.progress.value.positionMillis)

        // 只允许一次：之后仍无新帧也不再刷新。
        advanceTimeBy(PlaybackController.SURFACE_REFRESH_DELAY_MILLIS * 5)
        runCurrent()
        assertEquals(1, engine.refreshSurfaceCalls)
    }

    @Test
    fun `Surface 重挂后收到新帧就不再刷新`() = runTest {
        val controller = playingController()
        controller.setRenderSurfaceAvailable(false)
        controller.setRenderSurfaceAvailable(true)

        engine.reportFramePresented()
        advanceTimeBy(PlaybackController.SURFACE_REFRESH_DELAY_MILLIS * 2)
        runCurrent()

        assertEquals(0, engine.refreshSurfaceCalls)
    }

    @Test
    fun `关闭后台播放时退到后台先暂停再缓存当前帧`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)
        policy.setBackgroundPlaybackEnabled(false)

        policy.setAppInForeground(false)
        capture(controller)

        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `开启后台播放且正在播放时退到后台不缓存`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)

        policy.setAppInForeground(false)
        capture(controller)

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
        capture(controller)

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
        capture(controller)

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

    @Test
    fun `暂停态抓帧确认有重试上限`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        engine.frameToGrab = null
        reattach(controller)
        engine.reportSurfaceReconfigured()
        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS * (PlaybackController.BACKGROUND_FRAME_PROBE_MAX_ATTEMPTS + 2))
        runCurrent()
        val callsAfterLimit = engine.grabFrameCalls

        advanceTimeBy(PlaybackController.FRAME_PROBE_RETRY_MILLIS * 5)
        runCurrent()

        assertEquals(callsAfterLimit, engine.grabFrameCalls)
        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `抓帧任务不阻塞调用方且超时后丢弃结果`() = runBlocking {
        val gate = CountDownLatch(1)
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        engine.beforeGrab = { gate.await() }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val controller = PlaybackController(
                engine,
                progress,
                this,
                frameProbeDispatcher = dispatcher,
            )
            openPaused(controller)
            val startedAt = System.nanoTime()

            val job = controller.beginBackgroundCapture()

            assertTrue(job != null)
            // 抓帧在后台线程等待，调用方立即返回。
            assertTrue(System.nanoTime() - startedAt < 100_000_000L)
            controller.abandonBackgroundCapture()
            gate.countDown()
            job?.join()

            assertNull(controller.backgroundRestoreFrame.value)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `假引擎重复挂载同一 Surface 不重复递增代数`() {
        val surface = testSurface()

        engine.attachSurface(surface)
        engine.attachSurface(surface)
        assertEquals(1, engine.surfaceGeneration)

        engine.detachSurface()
        engine.attachSurface(surface)
        assertEquals(2, engine.surfaceGeneration)
    }

    private fun openPaused(controller: PlaybackController) {
        val files = listOf(
            MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"),
            MediaFile(name = "ep2.mkv", location = "root/ep2.mkv"),
        )
        controller.openPlaylist(files, files.first())
        engine.reportMediaLoaded()
        controller.pause()
        engine.reportPauseChanged(true)
    }

    private suspend fun capture(controller: PlaybackController) {
        controller.beginBackgroundCapture()?.join()
    }

    private fun TestScope.controller(
        frameProbeDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): PlaybackController =
        PlaybackController(
            engine,
            progress,
            backgroundScope,
            frameProbeDispatcher = frameProbeDispatcher,
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
        capture(controller)
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
    private fun testBitmap(): Bitmap = unsafeAllocate(Bitmap::class.java)

    private fun testSurface(): Surface = unsafeAllocate(Surface::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> unsafeAllocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, type) as T
    }
}
