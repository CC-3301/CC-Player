package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import com.cc3301.ccplayer.ui.PlayerPlayPauseCommand
import com.cc3301.ccplayer.ui.applyPlayPauseCommand
import com.cc3301.ccplayer.ui.onPlayPauseClick
import com.cc3301.ccplayer.ui.playPauseCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放键的真实链路：点击走 [onPlayPauseClick]，按引擎上报状态与意图解析成显式命令后投递。
 * 同一击重复投递不翻转；两次真人点击各自生效；切集窗口不按 Loading 落到翻转分支。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerPlayPauseCommandTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()
    private val dispatcher = UnconfinedTestDispatcher()
    private val controller = PlaybackController(
        engine,
        progress,
        CoroutineScope(dispatcher),
        nowMillis = { dispatcher.scheduler.currentTime },
    )

    @Test
    fun `同一击重复投递暂停仍是暂停`() {
        openPlaying()
        val command = playPauseCommand(
            controller.state.value,
            controller.switching.value,
            controller.playPauseIntentPaused,
        )!!

        applyPlayPauseCommand(command, pause = { controller.pause() }, resume = { controller.resume() })
        applyPlayPauseCommand(command, pause = { controller.pause() }, resume = { controller.resume() })

        assertEquals(PlayerPlayPauseCommand.Pause, command)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(2, engine.pauseCalls)
        assertEquals(0, engine.resumeCalls)
    }

    @Test
    fun `两次真实点击依次暂停与继续`() {
        openPlaying()

        controller.onPlayPauseClick()
        assertEquals(PlaybackState.Paused, controller.state.value)

        controller.onPlayPauseClick()
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(1, engine.pauseCalls)
        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `切集窗口点击按意图投递不翻转`() {
        openSwitching()
        assertFalse(controller.playPauseIntentPaused)

        // 切集起点意图是继续播放：第一击表达暂停，命令是显式暂停。
        val firstCommand = playPauseCommand(
            controller.state.value,
            controller.switching.value,
            controller.playPauseIntentPaused,
        )!!
        assertEquals(PlayerPlayPauseCommand.Pause, firstCommand)

        applyPlayPauseCommand(firstCommand, pause = { controller.pause() }, resume = { controller.resume() })
        applyPlayPauseCommand(firstCommand, pause = { controller.pause() }, resume = { controller.resume() })
        assertTrue(controller.playPauseIntentPaused)

        // 第二击按当前意图表达继续，两次点击各自生效。
        controller.onPlayPauseClick()
        assertFalse(controller.playPauseIntentPaused)
    }

    @Test
    fun `播完后点击重开当前文件`() {
        openPlaying()
        engine.reportPlaybackEnded()
        assertEquals(PlaybackState.Ended, controller.state.value)
        val startsBefore = engine.startedUrls.size

        controller.onPlayPauseClick()

        assertEquals(startsBefore + 1, engine.startedUrls.size)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `界面状态落后时按引擎上报状态投递`() {
        openPlaying()
        // 引擎已经暂停，界面拿到的是上一帧的播放态。
        engine.reportPauseChanged(true)

        assertEquals(
            PlayerPlayPauseCommand.Resume,
            playPauseCommand(
                controller.state.value,
                controller.switching.value,
                controller.playPauseIntentPaused,
            ),
        )
    }

    private fun openPlaying() {
        controller.openPlaylist(listOf(media), media)
        engine.reportMediaLoaded()
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    private fun openSwitching() {
        val first = mediaFile("ep1.mkv")
        val second = mediaFile("ep2.mkv")
        controller.openPlaylist(listOf(first, second), first)
        engine.reportMediaLoaded()
        controller.next()

        assertEquals(PlaybackState.Loading, controller.state.value)
        assertTrue(controller.switching.value)
    }

    private fun mediaFile(name: String): MediaFile =
        MediaFile(name = name, location = "root/$name")

    private companion object {
        val media = MediaFile(name = "episode1.mkv", location = "content://media/external/video/media/42")
    }
}
