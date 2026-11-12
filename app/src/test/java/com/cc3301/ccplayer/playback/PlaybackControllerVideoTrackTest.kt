package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 离开播放页继续播放时关闭视频轨：只改轨道选择，不拆 Surface、不重建输出，也不碰音频。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerVideoTrackTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `关闭视频轨不暂停播放也不发音频命令`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(30_000, 600_000)
        engine.fadeInDurations.clear()

        controller.setVideoTrackEnabled(false)

        assertEquals(listOf(false), engine.videoTrackEnabledCalls)
        assertEquals(0, engine.pauseCalls)
        assertEquals(0, engine.resumeCalls)
        assertEquals(0, engine.stopCalls)
        assertTrue(engine.muteCalls.isEmpty())
        assertTrue(engine.fadeInDurations.isEmpty())
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(30_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `关闭视频轨期间切集按文件加载完成就绪`() = runTest {
        val controller = playingController()
        controller.setVideoTrackEnabled(false)
        controller.next()
        assertEquals(PlaybackState.Loading, controller.state.value)

        engine.reportFileLoaded()
        runCurrent()

        // 不产生新视频帧：文件打开即就绪，不等首帧。
        assertFalse(controller.switching.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `重新选回视频轨后播放状态不变`() = runTest {
        val controller = playingController()

        controller.setVideoTrackEnabled(false)
        controller.setVideoTrackEnabled(true)

        assertEquals(listOf(false, true), engine.videoTrackEnabledCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(0, engine.stopCalls)
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
        controller.openPlaylistForTest(TEST_ENTRY_GENERATION, files, files.first())
        engine.reportMediaLoaded()
        return controller
    }
}
