package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import android.view.Surface
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
    }

    @Test
    fun `同一后台周期不重复抓帧`() = runTest {
        val first = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(first)
        val controller = pausedController()

        capture(controller)
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        capture(controller)

        assertSame(first, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `播放中退到后台不截帧`() = runTest {
        val controller = playingController()

        capture(controller)

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `没有会话时不截帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = controller()

        capture(controller)

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `加载中不截帧`() = runTest {
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())
        val controller = controller()
        val files = listOf(MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"))
        controller.openPlaylist(files, files.first())
        controller.pause()

        capture(controller)

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `纯音频抓不到帧时不缓存`() = runTest {
        // 引擎返回 null 表示抓不到帧；非 null Frame 表示有帧（位图仅为测试替身可空）。
        engine.frameToGrab = null
        val controller = pausedController()

        capture(controller)

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

        capture(controller)

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
    fun `暂停态回前台 seek 到当前位置逼出帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        engine.reportPositionChanged(42_000, 600_000)

        reattach(controller)
        runCurrent()

        // 暂停态不会自然出帧：seek 到当前位置，播放位置不变。
        assertEquals(listOf(42_000L), engine.seekPositions)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(42_000L, controller.progress.value.positionMillis)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        // seek 完成前不算画面恢复，缓存帧先保留。
        assertSame(frame, controller.backgroundRestoreFrame.value)

        engine.reportLoadActivity(LoadActivity.PlaybackRestart)
        runCurrent()

        // seek 之后的抓帧成功才算画面恢复。
        assertNull(controller.backgroundRestoreFrame.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `暂停态回前台 seek 后抓不到帧时保留旧画面`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        engine.frameToGrab = null

        reattach(controller)
        engine.reportLoadActivity(LoadActivity.PlaybackRestart)
        runCurrent()

        assertEquals(1, engine.seekPositions.size)
        assertSame(frame, controller.backgroundRestoreFrame.value)

        // 等待有上限：超时后不再 seek，旧画面保持。
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS * 3)
        runCurrent()
        assertEquals(1, engine.seekPositions.size)
        assertSame(frame, controller.backgroundRestoreFrame.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `播放态回前台先等画面自然输出再 seek`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)

        reattach(controller)
        runCurrent()
        assertTrue(engine.seekPositions.isEmpty())

        advanceTimeBy(PlaybackController.SURFACE_NATURAL_FRAME_WAIT_MILLIS)
        runCurrent()

        // 等不到自然出帧才 seek 到当前位置；位置与加载目标都不变。
        assertEquals(listOf(30_000L), engine.seekPositions)
        assertEquals(30_000L, controller.progress.value.positionMillis)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)

        // 只 seek 一次，不会反复逼帧。
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS * 3)
        runCurrent()
        assertEquals(1, engine.seekPositions.size)
    }

    @Test
    fun `播放态回前台画面自然输出就不 seek`() = runTest {
        val controller = playingController()

        reattach(controller)
        engine.reportFramePresented()
        advanceTimeBy(PlaybackController.SURFACE_NATURAL_FRAME_WAIT_MILLIS * 3)
        runCurrent()

        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `恢复播放并出帧后才撤掉缓存帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        reattach(controller)
        controller.resume()
        engine.reportPauseChanged(false)

        engine.reportPositionChanged(1_000, 600_000)
        engine.reportFramePresented()

        assertNull(controller.backgroundRestoreFrame.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
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
        val generationBefore = engine.surfaceGeneration

        // 退后台：播放继续，画面停止渲染。
        policy.setAppInForeground(false)
        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
        policy.setAppInForeground(true)

        assertTrue(engine.surfaceGeneration > generationBefore)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(30_000L, controller.progress.value.positionMillis)

        // 回前台走重挂后的出帧路径：位置前进过一次，不再 seek。
        engine.reportPositionChanged(31_000, 600_000)
        engine.reportFramePresented()
        assertNull(controller.backgroundRestoreFrame.value)
        advanceTimeBy(PlaybackController.FRAME_FALLBACK_MILLIS * 2)
        runCurrent()
        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `后台播放关闭时回前台显示暂停帧并保持暂停`() = runTest {
        val frame = testBitmap()
        engine.frameToGrab = PlaybackEngine.Frame(frame)
        val controller = playingController()
        val policy = BackgroundPlaybackPolicy(pausePlayback = controller::pauseForBackground)
        policy.setBackgroundPlaybackEnabled(false)
        val generationBefore = engine.surfaceGeneration

        // 退后台：立即暂停，再抓一帧留给回前台。
        policy.setAppInForeground(false)
        engine.reportPauseChanged(true)
        capture(controller)
        controller.setRenderSurfaceAvailable(false)
        engine.reportSurfaceReattached()
        controller.setRenderSurfaceAvailable(true)
        policy.setAppInForeground(true)

        assertTrue(engine.surfaceGeneration > generationBefore)
        // 暂停态回前台 seek 到当前位置逼出帧，seek 完成前缓存帧仍在。
        assertEquals(1, engine.seekPositions.size)
        assertSame(frame, controller.backgroundRestoreFrame.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)

        engine.reportLoadActivity(LoadActivity.PlaybackRestart)
        runCurrent()

        assertNull(controller.backgroundRestoreFrame.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
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
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            frameProbeDispatcher = frameProbeDispatcher,
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
