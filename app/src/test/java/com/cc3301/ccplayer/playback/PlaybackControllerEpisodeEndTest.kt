package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控制层统一提供片尾事实：已结束、暂停在片尾与交接窗口内旧会话播完都算片尾。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerEpisodeEndTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    private fun TestScope.controller(): PlaybackController = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        nowMillis = { testScheduler.currentTime },
    )

    private fun mediaFile(name: String): MediaFile = MediaFile(name = name, location = "root/${name}")

    private fun TestScope.playingController(): PlaybackController {
        val controller = controller()
        val files = listOf(mediaFile("a.mkv"), mediaFile("b.mkv"))
        controller.openPlaylistForTest(files, files.first())
        engine.reportMediaLoaded()
        return controller
    }

    @Test
    fun `已结束算片尾`() = runTest {
        val controller = playingController()
        controller.setAutoPlayNext(false)

        engine.reportPlaybackEnded()

        assertTrue(controller.atEpisodeEnd)
    }

    @Test
    fun `暂停在片尾位置算片尾`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)

        controller.pause()

        assertTrue(controller.atEpisodeEnd)
    }

    @Test
    fun `暂停在片尾之外不算片尾`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(500_000, 600_000)

        controller.pause()

        assertFalse(controller.atEpisodeEnd)
    }

    @Test
    fun `播放中不算片尾`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(600_000, 600_000)

        assertFalse(controller.atEpisodeEnd)
    }

    @Test
    fun `交接窗口内旧会话播完算片尾`() = runTest {
        val controller = playingController()
        val oldToken = engine.currentLoadToken

        controller.beginOpenHandover(generation = 2)
        engine.reportPlaybackEnded(loadToken = oldToken)

        // 状态仍是 Playing，但控制层提供片尾事实；返回与失败收尾据此按片尾处理。
        assertTrue(controller.atEpisodeEnd)
    }

    @Test
    fun `新目标开始加载后旧会话片尾事实失效`() = runTest {
        val controller = playingController()
        val oldToken = engine.currentLoadToken
        controller.beginOpenHandover(generation = 2)
        engine.reportPlaybackEnded(loadToken = oldToken)
        assertTrue(controller.atEpisodeEnd)

        controller.openPlaylist(
            files = listOf(mediaFile("c.mkv")),
            current = mediaFile("c.mkv"),
            generation = 2,
        )

        assertFalse(controller.atEpisodeEnd)
    }
}
