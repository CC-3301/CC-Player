package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
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
import org.junit.Assert.assertNotNull
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

        engine.reportFramePresented()

        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `暂停态回前台不 seek，缓存帧留到下一次出帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        engine.reportPositionChanged(42_000, 600_000)

        reattach(controller)
        runCurrent()

        // 暂停态不会产生出帧通知：不 seek、不改播放位置，缓存帧继续盖住画面。
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(42_000L, controller.progress.value.positionMillis)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `挂载后 3 秒仍无新帧只翻转一次视频输出`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)

        reattach(controller)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS - 1)
        runCurrent()
        assertEquals(0, engine.flipVideoOutputCalls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.flipVideoOutputCalls)
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertSame(frame, controller.backgroundRestoreFrame.value)

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
        assertNull(controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `Surface 分离时取消恢复等待`() = runTest {
        val controller = capturedController()

        reattach(controller)
        controller.setRenderSurfaceAvailable(false)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()

        assertEquals(0, engine.flipVideoOutputCalls)
    }

    @Test
    fun `Surface 未销毁时回前台立刻撤掉缓存帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)

        // Surface 一直可用，没有进入恢复等待：画面本来就在，缓存帧多余。
        controller.onReturnedToForeground()

        assertNull(controller.backgroundRestoreFrame.value)
        assertEquals(0, engine.flipVideoOutputCalls)
    }

    @Test
    fun `重挂进入恢复等待时回前台保留缓存帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        reattach(controller)

        // 已进入恢复等待：缓存帧留到播放核心的首个新帧。
        controller.onReturnedToForeground()

        assertSame(frame, controller.backgroundRestoreFrame.value)
    }

    @Test
    fun `Surface 尚未重挂时回前台保留缓存帧`() = runTest {
        val frame = testBitmap()
        val controller = capturedController(frame)
        controller.setRenderSurfaceAvailable(false)

        // onStart 可能早于 surfaceCreated：没有可用画面时不清帧，也不进入恢复等待。
        controller.onReturnedToForeground()

        assertSame(frame, controller.backgroundRestoreFrame.value)
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
    fun `片尾黑底重挂后不产生缓存帧`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        controller.next()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertTrue(controller.holdBlack.value)

        reattach(controller)
        runCurrent()

        // 片尾黑底没有画面可盖：未进入恢复等待，清帧路径不产生遮罩。
        assertNull(controller.backgroundRestoreFrame.value)
        assertEquals(0, engine.flipVideoOutputCalls)
    }

    @Test
    fun `旧加载的迟到帧不撤切集冻结画面`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(10_000, 600_000)
        engine.frameToGrab = PlaybackEngine.Frame(testBitmap())

        controller.next()
        val freeze = controller.freezeFrame.value
        assertNotNull(freeze)

        // 原片代际的迟到出帧不能撤掉切集冻结画面。
        engine.reportFramePresented(loadToken = 1)
        assertSame(freeze, controller.freezeFrame.value)

        // 当前目标加载的帧才能撤。
        engine.reportFramePresented(loadToken = engine.currentLoadToken)
        assertNull(controller.freezeFrame.value)
    }

    @Test
    fun `片尾黑底重挂不等待也不翻转`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)
        controller.next()
        engine.reportPlaybackFailed()
        engine.reportFileLoaded()
        assertTrue(controller.holdBlack.value)

        reattach(controller)
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

        // 退后台：播放继续，画面停止渲染。
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
        assertNull(controller.backgroundRestoreFrame.value)
        advanceTimeBy(PlaybackController.SURFACE_RESTORE_WAIT_MILLIS * 2)
        runCurrent()
        assertTrue(engine.seekPositions.isEmpty())
        assertEquals(0, engine.flipVideoOutputCalls)
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
        controller.setRenderSurfaceAvailable(true)
        policy.setAppInForeground(true)

        // 暂停态回前台不 seek：缓存帧盖住画面，保持暂停。
        assertTrue(engine.seekPositions.isEmpty())
        assertSame(frame, controller.backgroundRestoreFrame.value)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(0, engine.resumeCalls)
        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
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
    fun `回前台放弃在途抓帧结果`() = runBlocking {
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
            val job = controller.beginBackgroundCapture()

            // Surface 未销毁就回到前台：在途抓帧的结果不能跨进前台。
            controller.onReturnedToForeground()
            gate.countDown()
            job?.join()

            assertNull(controller.backgroundRestoreFrame.value)
        } finally {
            dispatcher.close()
        }
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
        controller.setRenderSurfaceAvailable(true)
    }

    /**
     * 测试只需要一个非空帧身份；android.jar 的 Bitmap 构造器在单测里是桩，
     * 沿用现有测试的 Unsafe 分配方式。
     */
    private fun testBitmap(): Bitmap = unsafeAllocate(Bitmap::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> unsafeAllocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, type) as T
    }
}
