package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落定通知的序号：进程级单调，跨控制器实例也不回退，界面据此只撤掉比它旧的进度保持值。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettlementSequenceTest {

    private val files = listOf(MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"))

    @Test
    fun `同一目标连续两次落定各通知一次，id 递增`() = runTest {
        val engine = FakePlaybackEngine()
        val controller = controller(engine)

        val first = land(controller, engine, 120_000)
        val second = land(controller, engine, 120_000)

        assertEquals(120_000L, first.targetMillis)
        assertEquals(120_000L, second.targetMillis)
        assertTrue(second.id > first.id)
    }

    @Test
    fun `跨控制器实例的落定 id 继续递增`() = runTest {
        val firstEngine = FakePlaybackEngine()
        val firstController = controller(firstEngine)
        val first = land(firstController, firstEngine, 120_000)

        val secondEngine = FakePlaybackEngine()
        val secondController = controller(secondEngine)
        assertNull(secondController.seekSettlement.value)
        val second = land(secondController, secondEngine, 120_000)

        assertTrue(second.id > first.id)
    }

    /** 拖到目标、松手、再让播放核心回报这一跳已执行，返回本次落定通知。 */
    private fun land(controller: PlaybackController, engine: FakePlaybackEngine, targetMillis: Long): SeekSettlement {
        controller.updateSeekPreview(requestSeek = true, positionMillis = targetMillis)
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        return controller.seekSettlement.value ?: error("落定没有通知")
    }

    private fun TestScope.controller(engine: FakePlaybackEngine): PlaybackController {
        val controller = PlaybackController(
            engine,
            FakePlaybackProgressRepository(),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            frameProbeDispatcher = UnconfinedTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )
        controller.openPlaylist(files, files.first())
        engine.reportMediaLoaded()
        engine.reportPositionChanged(60_000, 600_000)
        return controller
    }
}
