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
    fun `换片后解码模式保持不变`() {
        controller.openPlaylist(listOf(mediaFile("a.mkv")), mediaFile("a.mkv"))
        engine.reportMediaLoaded()
        controller.setDecodeMode(DecodeMode.Software)
        engine.actions.clear()
        engine.decodeModes.clear()

        controller.openPlaylist(listOf(mediaFile("b.mkv")), mediaFile("b.mkv"))

        // 换片必须先补写解码模式，再开始加载新文件；不能只剩一次 start。
        assertEquals(listOf("decode:Software", "start"), engine.actions)
        assertEquals(listOf(DecodeMode.Software), engine.decodeModes)
        assertEquals(DecodeMode.Software, controller.decodeMode.value)
    }

    @Test
    fun `离开播放页后自动下一集开启时播完继续`() {
        // 离开播放页等同于 Surface 不可用：音频仍继续，播完按开关决定连播。
        controller.setRenderSurfaceAvailable(false)
        controller.openPlaylist(
            listOf(mediaFile("a.mkv"), mediaFile("b.mkv")),
            mediaFile("a.mkv"),
        )
        engine.reportMediaLoaded()

        engine.reportPlaybackEnded()

        assertEquals(listOf("root/a.mkv", "root/b.mkv"), engine.startedUrls)
        assertEquals(1, controller.currentIndex.value)
    }

    @Test
    fun `离开播放页后自动下一集关闭时播完停止`() {
        controller.setAutoPlayNext(false)
        controller.setRenderSurfaceAvailable(false)
        controller.openPlaylist(
            listOf(mediaFile("a.mkv"), mediaFile("b.mkv")),
            mediaFile("a.mkv"),
        )
        engine.reportMediaLoaded()

        engine.reportPlaybackEnded()

        assertEquals(listOf("root/a.mkv"), engine.startedUrls)
        assertEquals(PlaybackState.Ended, controller.state.value)
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
    fun `首次加载失败重发后仍失败进入错误态`() {
        controller.openPlaylist(listOf(mediaFile("a.mkv")), mediaFile("a.mkv"))

        // 首次失败先按加载看护重发一次，第二次失败才收尾为错误态。
        engine.reportPlaybackFailed("无法打开文件")
        engine.reportPlaybackFailed("无法打开文件")

        assertEquals(2, engine.startedUrls.size)
        assertTrue(controller.playbackFailed.value)
    }

    private fun mediaFile(name: String): MediaFile = MediaFile(name = name, location = "root/$name")
}
