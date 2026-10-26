package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动与滚轮的实时画面预览：节流下发真实 seek、预览期间静音、松手精确收尾，
 * 且不参与加载与切集的状态判定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSeekPreviewTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `drag preview seeks are throttled to about one per interval`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(70_000)
        controller.updateSeekPreview(80_000)

        // 一帧内的多次移动只能下发一次，避免把播放核心压垮。
        assertEquals(listOf(70_000L), engine.seekPositions)

        advanceTimeBy(PlaybackController.PREVIEW_SEEK_INTERVAL_MILLIS)
        controller.updateSeekPreview(90_000)

        assertEquals(listOf(70_000L, 90_000L), engine.seekPositions)
    }

    @Test
    fun `preview seeks mute the audio and only the release fades in`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size

        controller.updateSeekPreview(70_000)
        advanceTimeBy(PlaybackController.PREVIEW_SEEK_INTERVAL_MILLIS)
        controller.updateSeekPreview(80_000)

        // 预览 seek 各自淡入会造成拖动中断续爆音：只静音一次，不逐次淡入。
        assertEquals(listOf(true), engine.muteCalls)
        assertEquals(fadesBeforePreview, engine.fadeInDurations.size)

        controller.commitSeekPreview()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(80_000L, engine.seekPositions.last())
        assertEquals(fadesBeforePreview + 1, engine.fadeInDurations.size)
        assertEquals(PlaybackController.FADE_IN_MILLIS, engine.fadeInDurations.last())
    }

    @Test
    fun `release lands the exact preview target after a throttled drag`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(70_000)
        controller.updateSeekPreview(130_000)
        assertEquals(listOf(70_000L), engine.seekPositions)

        // 被节流掉的中间位置由收尾 seek 精确补上。
        controller.commitSeekPreview()

        assertEquals(130_000L, engine.seekPositions.last())
        assertNull(controller.seekPreviewMillis.value)
    }

    @Test
    fun `dragging during the first load does not preview`() = runTest {
        val controller = controller()
        controller.openPlaylist(
            listOf(MediaFile(name = "ep1.mkv", location = "root/ep1.mkv")),
            MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"),
        )

        controller.updateSeekPreview(30_000)

        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())
    }

    @Test
    fun `dragging during a switch does not preview or create ready evidence`() = runTest {
        val controller = playingController()
        controller.next()
        assertTrue(controller.switching.value)

        controller.updateSeekPreview(30_000)

        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())
        assertTrue(controller.switching.value)
    }

    @Test
    fun `dragging with an unknown duration does not preview`() = runTest {
        val controller = playingController()

        controller.updateSeekPreview(30_000)

        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())
    }

    @Test
    fun `preview seeks are not saved as playback progress`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(10_000, 600_000)
        runCurrent()
        progress.records.clear()

        controller.updateSeekPreview(300_000)
        engine.reportPositionChanged(300_000, 600_000)
        runCurrent()

        // 预览挪动的位置不是用户落定的记录，只有收尾 seek 之后才写库。
        assertNull(progress.records["root/ep1.mkv"])
    }

    @Test
    fun `wheel steps accumulate from the pending preview target`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        assertEquals(55_000L, controller.updateSeekPreviewBy(-5_000))
        advanceTimeBy(PlaybackController.PREVIEW_SEEK_INTERVAL_MILLIS)
        assertEquals(50_000L, controller.updateSeekPreviewBy(-5_000))

        assertEquals(listOf(55_000L, 50_000L), engine.seekPositions)
    }

    @Test
    fun `a wheel step at the file start does nothing`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(0, 600_000)

        assertNull(controller.updateSeekPreviewBy(-5_000))

        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())
    }

    @Test
    fun `a new session restores the audio muted by a preview`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(70_000)
        assertEquals(listOf(true), engine.muteCalls)

        controller.next()

        assertEquals(listOf(true, false), engine.muteCalls)
    }

    private fun TestScope.controller(): PlaybackController = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        frameProbeDispatcher = UnconfinedTestDispatcher(testScheduler),
        nowMillis = { testScheduler.currentTime },
    )

    /** 起播第一集并等首帧就绪，作为正常播放状态。 */
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
}
