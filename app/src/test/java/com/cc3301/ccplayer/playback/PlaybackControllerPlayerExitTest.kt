package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放页离开后的播放替换与错误态判定。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerPlayerExitTest {

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
    fun `正在播放时点开另一条视频以新列表替换当前播放`() {
        controller.openPlaylist(listOf(mediaFile("a.mkv"), mediaFile("b.mkv")), mediaFile("a.mkv"))
        engine.reportMediaLoaded()

        controller.openPlaylist(listOf(mediaFile("c.mkv")), mediaFile("c.mkv"))

        assertEquals(listOf("root/a.mkv", "root/c.mkv"), engine.startedUrls)
        assertEquals(1, engine.stopCalls)
        assertEquals(listOf("c.mkv"), controller.playlist.value.map { it.name })
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `播放失败进入错误态并可由新的加载清除`() {
        controller.openPlaylist(listOf(mediaFile("a.mkv")), mediaFile("a.mkv"))
        engine.reportMediaLoaded()

        engine.reportPlaybackFailed("解码失败")

        assertTrue(controller.playbackFailed.value)
        assertEquals(PlaybackState.Ended, controller.state.value)

        controller.openPlaylist(listOf(mediaFile("b.mkv")), mediaFile("b.mkv"))

        assertFalse(controller.playbackFailed.value)
    }

    @Test
    fun `初次加载失败也算错误态`() {
        controller.openPlaylist(listOf(mediaFile("a.mkv")), mediaFile("a.mkv"))

        engine.reportPlaybackFailed("无法打开文件")

        assertTrue(controller.playbackFailed.value)
    }

    private fun mediaFile(name: String): MediaFile = MediaFile(name = name, location = "root/$name")
}
