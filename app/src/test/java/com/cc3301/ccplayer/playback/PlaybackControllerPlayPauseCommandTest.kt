package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import com.cc3301.ccplayer.ui.PlayerPlayPauseCommand
import com.cc3301.ccplayer.ui.playPauseCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放/暂停键按引擎上报状态解析成显式命令：同一击被重复投递时结果不变，
 * 用户两次点击仍是两次独立切换，界面自己的状态不参与判据。
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
        val command = playPauseCommand(controller.state.value)

        deliver(command)
        deliver(command)

        assertEquals(PlayerPlayPauseCommand.Pause, command)
        assertEquals(PlaybackState.Paused, controller.state.value)
        assertEquals(2, engine.pauseCalls)
        assertEquals(0, engine.resumeCalls)
    }

    @Test
    fun `两次真实点击依次暂停与继续`() {
        openPlaying()

        deliver(playPauseCommand(controller.state.value))
        assertEquals(PlaybackState.Paused, controller.state.value)

        deliver(playPauseCommand(controller.state.value))
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(1, engine.pauseCalls)
        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `界面状态落后时按引擎上报状态投递`() {
        openPlaying()
        // 引擎已经暂停，界面拿到的是上一帧的播放态。
        engine.reportPauseChanged(true)

        assertEquals(PlayerPlayPauseCommand.Resume, playPauseCommand(controller.state.value))
    }

    private fun openPlaying() {
        controller.openPlaylist(listOf(media), media)
        engine.reportMediaLoaded()
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    /** 与界面接线一致：命令按引擎上报状态解析后投递，播完与未就绪交给控制层收尾。 */
    private fun deliver(command: PlayerPlayPauseCommand?) {
        when (command) {
            PlayerPlayPauseCommand.Pause -> controller.pause()
            PlayerPlayPauseCommand.Resume -> controller.resume()
            null -> controller.togglePlayPause()
        }
    }

    private companion object {
        val media = MediaFile(name = "episode1.mkv", location = "content://media/external/video/media/42")
    }
}
