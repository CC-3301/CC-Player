package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开另一个目标的交接：旧片立即停止出声、屏上旧画面撤掉铺黑，方向与比例按新目标判定；
 * 旧代际迟到的完成、出帧与失败一律丢弃；切集维持旧画面保留到目标首帧。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerOpenHandoverTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    private fun TestScope.controller(): PlaybackController = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        nowMillis = { testScheduler.currentTime },
    )

    /** 起播第一集并等首帧就绪，作为打开新目标前的旧会话。 */
    private fun TestScope.playingController(
        vararg names: String = arrayOf("a.mkv", "b.mkv"),
    ): PlaybackController {
        val controller = controller()
        open(controller, *names)
        engine.reportVideoSizeChanged(1920, 1080)
        engine.reportMediaLoaded()
        return controller
    }

    @Test
    fun `打开另一个目标先让旧片停止出声`() = runTest {
        val controller = playingController()

        controller.beginOpenHandover()

        // 旧片立即静音，不等新目标就绪，也不动播放列表与加载阶段。
        assertEquals(listOf(true), engine.muteCalls)
        assertEquals(listOf("root/a.mkv"), engine.startedUrls)
    }

    @Test
    fun `打开另一个目标撤掉旧画面并铺静默黑屏`() = runTest {
        val controller = playingController()
        assertTrue(controller.surfacePresentation.value.showsPicture)

        controller.beginOpenHandover()

        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertTrue(controller.surfacePresentation.value.quietBlack)
        // 旧目标的比例不再挂在屏上：方向按新目标判定。
        assertNull(controller.surfacePresentation.value.aspectRatio)
    }

    @Test
    fun `打开交接期间旧目标迟到的就绪不放声不上屏`() = runTest {
        val controller = playingController()
        val oldToken = engine.currentLoadToken

        controller.beginOpenHandover()
        // 旧片还在静音播放，迟到的完成与出帧都不属于新目标。
        engine.reportMediaLoaded(loadToken = oldToken)
        engine.reportFramePresented(loadToken = oldToken)

        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertTrue(controller.surfacePresentation.value.quietBlack)
        assertEquals(listOf(true), engine.muteCalls)
    }

    @Test
    fun `旧目标迟到的尺寸上报不改新目标的方向`() = runTest {
        val controller = playingController()

        controller.beginOpenHandover()
        engine.reportVideoSizeChanged(1920, 1080)
        assertNull(controller.surfacePresentation.value.aspectRatio)

        controller.openPlaylist(listOf(mediaFile("c.mkv")), mediaFile("c.mkv"))
        engine.reportVideoSizeChanged(1080, 1920)
        engine.reportMediaLoaded()

        assertEquals(1080.0 / 1920.0, controller.surfacePresentation.value.aspectRatio!!, 0.0001)
    }

    @Test
    fun `新目标加载期间保持静默黑屏，出画面后才放声`() = runTest {
        val controller = playingController()

        controller.beginOpenHandover()
        controller.openPlaylist(listOf(mediaFile("c.mkv")), mediaFile("c.mkv"))

        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertTrue(controller.surfacePresentation.value.quietBlack)
        // loadfile 之后仍保持静音：播放核心处理替换之前旧片不能漏出残音。
        assertEquals(listOf(true), engine.muteCalls)

        // 旧目标迟到的首帧仍不算新目标。
        engine.reportFirstFrameReady(loadToken = engine.currentLoadToken - 1)
        assertFalse(controller.surfacePresentation.value.showsPicture)

        engine.reportMediaLoaded()

        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertFalse(controller.surfacePresentation.value.quietBlack)
        assertEquals(listOf(true, false), engine.muteCalls)
    }

    @Test
    fun `打开交接时旧会话仍在首次加载，旧目标就绪被丢弃`() = runTest {
        val controller = controller()
        open(controller, "a.mkv")

        controller.beginOpenHandover()
        assertEquals(listOf(true), engine.muteCalls)

        // 旧目标迟到的完成既不上屏也不放声。
        engine.reportMediaLoaded()
        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertEquals(listOf(true), engine.muteCalls)

        controller.openPlaylist(listOf(mediaFile("b.mkv")), mediaFile("b.mkv"))
        engine.reportMediaLoaded()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertTrue(controller.surfacePresentation.value.showsPicture)
    }

    @Test
    fun `打开交接时旧会话仍在切集，旧目标就绪被丢弃`() = runTest {
        val controller = playingController()
        controller.next()
        val switchToken = engine.currentLoadToken

        controller.beginOpenHandover()
        engine.reportMediaLoaded(loadToken = switchToken)
        assertFalse(controller.surfacePresentation.value.showsPicture)
        assertEquals(listOf(true), engine.muteCalls)

        controller.openPlaylist(listOf(mediaFile("d.mkv")), mediaFile("d.mkv"))
        engine.reportMediaLoaded()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertTrue(controller.surfacePresentation.value.showsPicture)
    }

    @Test
    fun `打开交接时旧会话仍在恢复原片，新目标能接管并放声`() = runTest {
        val controller = playingController()
        controller.next()
        // 两次失败后回退原片，进入恢复原片阶段。
        engine.reportPlaybackFailed()
        engine.reportPlaybackFailed()
        assertEquals(PlaybackState.Paused, controller.state.value)

        controller.beginOpenHandover()
        assertEquals(listOf(true), engine.muteCalls)

        controller.openPlaylist(listOf(mediaFile("e.mkv")), mediaFile("e.mkv"))
        engine.reportMediaLoaded()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertTrue(controller.surfacePresentation.value.showsPicture)
    }

    @Test
    fun `打开后首次加载失败，重发成功仍能放声`() = runTest {
        val controller = playingController()
        controller.beginOpenHandover()
        controller.openPlaylist(listOf(mediaFile("c.mkv")), mediaFile("c.mkv"))
        val firstToken = engine.currentLoadToken

        engine.reportPlaybackFailed()

        // 重发沿用同一代际，只换这次引擎加载的令牌。
        assertNotEquals(firstToken, engine.currentLoadToken)
        assertEquals(listOf("root/a.mkv", "root/c.mkv", "root/c.mkv"), engine.startedUrls)
        assertEquals(listOf(true), engine.muteCalls)

        engine.reportMediaLoaded()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `打开交接期间改目标不留静音与黑屏`() = runTest {
        val controller = playingController()

        controller.beginOpenHandover()
        // 交接还没落地就选下一集：切集接管，交接的静音随旧代际作废。
        controller.next()

        assertEquals(listOf(true, false), engine.muteCalls)

        engine.reportMediaLoaded()

        assertEquals(PlaybackState.Playing, controller.state.value)
        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertFalse(controller.surfacePresentation.value.quietBlack)
    }

    @Test
    fun `取消切集不留静音与黑屏`() = runTest {
        val controller = playingController()
        controller.next()

        assertTrue(controller.cancelSwitch())

        assertTrue(engine.muteCalls.isEmpty())
        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertFalse(controller.surfacePresentation.value.quietBlack)
    }

    @Test
    fun `交接期间停止会话不留静音`() = runTest {
        val controller = playingController()

        controller.beginOpenHandover()
        controller.stop()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    @Test
    fun `交接期间播完且不连播时结束会话并撤回静音`() = runTest {
        val controller = playingController()
        controller.beginOpenHandover()
        controller.openPlaylist(listOf(mediaFile("c.mkv")), mediaFile("c.mkv"))
        engine.reportMediaLoaded()
        controller.setAutoPlayNext(false)

        engine.reportPlaybackEnded()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `打开的目标两次失败后结束会话并撤回静音`() = runTest {
        val controller = playingController()
        controller.beginOpenHandover()
        controller.openPlaylist(listOf(mediaFile("c.mkv")), mediaFile("c.mkv"))

        engine.reportPlaybackFailed()
        engine.reportPlaybackFailed()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(PlaybackState.Ended, controller.state.value)
        assertTrue(controller.playbackFailed.value)
    }

    @Test
    fun `取消打开交接后回到交接前的画面与出声`() = runTest {
        val controller = playingController()

        controller.beginOpenHandover()
        controller.cancelOpenHandover()

        assertEquals(listOf(true, false), engine.muteCalls)
        // 画面按交接前的帧状态恢复，不依赖下一帧。
        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertFalse(controller.surfacePresentation.value.quietBlack)
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, 0.0001)
        assertEquals(0, engine.awaitFramePresentationCalls)
    }

    @Test
    fun `切集仍保留屏上旧画面`() = runTest {
        val controller = playingController()

        controller.next()

        // 切集维持现状：旧画面留到目标首帧，且不是静默黑屏。
        assertTrue(controller.surfacePresentation.value.showsPicture)
        assertFalse(controller.surfacePresentation.value.quietBlack)
        assertEquals(1920.0 / 1080.0, controller.surfacePresentation.value.aspectRatio!!, 0.0001)
    }

    @Test
    fun `预览静音与交接静音互不代管`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        // 预览先静音，随后打开另一个目标：两条来源叠加，命令仍只有一次。
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.beginOpenHandover()
        assertEquals(listOf(true), engine.muteCalls)

        // 预览收尾只撤自己那一份，不能替交接给旧片放声。
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        assertEquals(listOf(true), engine.muteCalls)

        controller.cancelOpenHandover()
        assertEquals(listOf(true, false), engine.muteCalls)
    }

    private fun mediaFile(name: String): MediaFile = MediaFile(name = name, location = "root/${name}")

    private fun open(controller: PlaybackController, vararg names: String) {
        val files = names.map { mediaFile(it) }
        controller.openPlaylist(files, files.first())
    }
}
