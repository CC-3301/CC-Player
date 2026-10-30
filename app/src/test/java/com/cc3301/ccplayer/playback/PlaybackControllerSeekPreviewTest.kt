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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动与滚轮的实时画面预览：节流下发真实 seek、预览期间静音、收尾精确落定，
 * 且不参与加载与切集的状态判定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSeekPreviewTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()

    @Test
    fun `拖动期间在途预览 seek 恒不超过一个，新目标只覆盖旧目标`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(70_000)
        controller.updateSeekPreview(90_000)
        controller.updateSeekPreview(120_000)
        advanceTimeBy(PlaybackController.PREVIEW_HEARTBEAT_MILLIS)

        // 在途只留一个 seek；中途目标被最后一个覆盖，队列不随拖动增长。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(120_000L, controller.seekPreviewMillis.value)

        // 回执到达后只补发被覆盖的最后一个目标。
        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 120_000L), engine.seekPositions)
    }

    @Test
    fun `与上次下发相差不足一秒的目标不重发预览 seek`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(70_000)
        engine.reportFirstFrameReady()

        // 死区内只更新显示目标，不产生新的 seek。
        controller.updateSeekPreview(70_400)
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(70_400L, controller.seekPreviewMillis.value)

        // 越过死区才重发。
        controller.updateSeekPreview(71_400)
        assertEquals(listOf(70_000L, 71_400L), engine.seekPositions)
    }

    @Test
    fun `快拖后立刻松手不回跳`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        // 快速拖到 2:00 立刻松手：预览与落定是同一个目标。
        controller.updateSeekPreview(120_000)
        controller.commitSeekPreview()
        assertEquals(120_000L, controller.progress.value.positionMillis)

        // 引擎下发 seek 时把位置乐观写成目标，随后迟到的旧位置样本才落盘。
        engine.reportPositionChanged(120_000, 600_000)
        engine.reportPositionChanged(80_000, 600_000)

        // 回执到达前进度停在目标上，圆球不回跳。
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(120_000L, controller.settledSeekMillis.value)
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportPositionChanged(120_040, 600_000)
        assertEquals(120_040L, controller.progress.value.positionMillis)
    }

    @Test
    fun `回执不到时连续两个越过目标的样本才算落定`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(120_000)
        controller.commitSeekPreview()

        // 单个样本可能是引擎下发 seek 时的乐观写回，不算到达。
        engine.reportPositionChanged(120_000, 600_000)
        assertNull(controller.settledSeekMillis.value)

        engine.reportPositionChanged(120_010, 600_000)
        assertEquals(120_000L, controller.settledSeekMillis.value)
    }

    @Test
    fun `preview seeks mute once and the release lands before restoring the audio`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size

        controller.updateSeekPreview(70_000)
        controller.updateSeekPreview(80_000)

        // 预览 seek 各自淡入会造成拖动中断续爆音：只静音一次，不逐次淡入。
        assertEquals(listOf(true), engine.muteCalls)
        assertEquals(fadesBeforePreview, engine.fadeInDurations.size)

        controller.commitSeekPreview()

        // 最后一档预览已经 seek 到同一目标：收尾只恢复声音并淡入，旧位置不会以满音量播出。
        assertEquals(
            listOf("muted(false)", "fade(500)"),
            engine.seekAudioCalls.takeLast(2),
        )
        assertEquals(listOf(70_000L, 80_000L), engine.seekPositions)
        assertEquals(fadesBeforePreview + 1, engine.fadeInDurations.size)
    }

    @Test
    fun `release lands the exact preview target after a covered drag`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(70_000)
        controller.updateSeekPreview(130_000)
        assertEquals(listOf(70_000L), engine.seekPositions)

        // 被覆盖掉的中间位置由收尾 seek 精确补上。
        controller.commitSeekPreview()

        assertEquals(130_000L, engine.seekPositions.last())
        assertNull(controller.seekPreviewMillis.value)
    }

    @Test
    fun `releasing at the last preview target does not seek again`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size

        controller.updateSeekPreview(70_000)
        controller.commitSeekPreview()

        // 同一目标再下发一次会把画面清空重解码，落定前先闪一下。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(listOf("muted(false)", "fade(500)"), engine.seekAudioCalls.takeLast(2))
        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(fadesBeforePreview + 1, engine.fadeInDurations.size)
        assertNull(controller.seekPreviewMillis.value)
    }

    @Test
    fun `a released preview restores the audio and the progress updates`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(70_000)
        assertEquals(listOf(true), engine.muteCalls)

        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        engine.reportPositionChanged(120_000, 600_000)

        assertEquals(listOf(true, false), engine.muteCalls)
        // 落定回执之后进度恢复更新，不再被预览吞掉。
        assertEquals(120_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `a stationary drag stays in preview while the heartbeat keeps coming`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(70_000)

        // 手指按住不动没有指针事件：心跳续命，兜底不能中途收尾。
        repeat(5) {
            advanceTimeBy(PlaybackController.PREVIEW_HEARTBEAT_MILLIS + 1)
            controller.seekPreviewHeartbeat()
            assertEquals(70_000L, controller.seekPreviewMillis.value)
            assertEquals(listOf(true), engine.muteCalls)
        }

        assertEquals(listOf(70_000L), engine.seekPositions)
    }

    @Test
    fun `a preview without heartbeat only restores the audio`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size
        controller.updateSeekPreview(70_000)

        // 心跳停了才收尾：不落定、不淡入，也不改写片尾这类与预览无关的状态。
        advanceTimeBy(PlaybackController.PREVIEW_IDLE_TIMEOUT_MILLIS + 1)

        assertEquals(listOf(true, false), engine.muteCalls)
        assertNull(controller.seekPreviewMillis.value)
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(fadesBeforePreview, engine.fadeInDurations.size)

        engine.reportPositionChanged(120_000, 600_000)
        assertEquals(120_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `an external seek lands before restoring the audio`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(70_000)

        controller.seekTo(200_000)

        // 与松手收尾同序：先 seek 到新目标，再恢复声音与淡入。
        assertEquals(
            listOf("seek(200000)", "muted(false)", "fade(500)"),
            engine.seekAudioCalls.takeLast(3),
        )
        assertEquals(listOf(70_000L, 200_000L), engine.seekPositions)
        assertNull(controller.seekPreviewMillis.value)

        engine.reportFirstFrameReady()
        engine.reportPositionChanged(200_000, 600_000)
        assertEquals(200_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `dragging during the first load does not preview`() = runTest {
        val controller = controller()
        open(controller)

        controller.updateSeekPreview(30_000)
        advanceTimeBy(PlaybackController.PREVIEW_IDLE_TIMEOUT_MILLIS + 1)

        assertNull(controller.seekPreviewMillis.value)
        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())
    }

    @Test
    fun `a preview during the first load leaves the buffering indicator alone`() = runTest {
        val controller = controller()
        open(controller)
        advanceTimeBy(PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS + 1)
        assertTrue(controller.buffering.value)

        controller.updateSeekPreview(30_000)

        assertTrue(controller.buffering.value)
        assertTrue(engine.seekPositions.isEmpty())
    }

    @Test
    fun `a preview during a switch creates no switch evidence`() = runTest {
        val controller = playingController()
        controller.next()
        advanceTimeBy(PlaybackController.BUFFER_INDICATOR_DELAY_MILLIS + 1)
        assertTrue(controller.buffering.value)
        val startedLoads = engine.startedUrls.size

        controller.updateSeekPreview(30_000)
        advanceTimeBy(PlaybackController.PREVIEW_IDLE_TIMEOUT_MILLIS + 1)

        // 预览既不推进加载，也不改出缓冲提示、不触发自动连播。
        assertTrue(controller.switching.value)
        assertTrue(controller.buffering.value)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(startedLoads, engine.startedUrls.size)
        assertNull(controller.seekPreviewMillis.value)
        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())
    }

    @Test
    fun `dragging while restoring the origin does not preview`() = runTest {
        val controller = playingController()
        controller.next()
        engine.reportPlaybackFailed()
        assertTrue(controller.restoring.value)

        controller.updateSeekPreview(30_000)

        assertNull(controller.seekPreviewMillis.value)
        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())
    }

    @Test
    fun `dragging with an unknown duration leaves no preview state`() = runTest {
        val controller = playingController()

        controller.updateSeekPreview(30_000)

        // 不允许预览时连标志都不写，进度不会被这条路径永久吞掉。
        assertNull(controller.seekPreviewMillis.value)
        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())

        engine.reportPositionChanged(120_000, 600_000)
        assertEquals(120_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `a wheel step with an unknown duration leaves no preview state`() = runTest {
        val controller = playingController()

        assertNull(controller.updateSeekPreviewBy(-5_000))

        assertNull(controller.seekPreviewMillis.value)
        assertTrue(engine.muteCalls.isEmpty())

        engine.reportPositionChanged(120_000, 600_000)
        assertEquals(120_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `a wheel step after playback ended leaves no preview state`() = runTest {
        val controller = playingController()
        controller.setAutoPlayNext(false)
        engine.reportPositionChanged(600_000, 600_000)
        engine.reportPlaybackEnded()
        assertEquals(PlaybackState.Ended, controller.state.value)

        assertNull(controller.updateSeekPreviewBy(-5_000))

        assertNull(controller.seekPreviewMillis.value)
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
    fun `a preview near the end does not start the next episode`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val startedLoads = engine.startedUrls.size

        controller.updateSeekPreview(599_000)
        controller.commitSeekPreview()

        assertFalse(controller.switching.value)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(startedLoads, engine.startedUrls.size)
    }

    @Test
    fun `wheel steps accumulate from the pending preview target`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        assertEquals(55_000L, controller.updateSeekPreviewBy(-5_000))
        engine.reportFirstFrameReady()
        assertEquals(50_000L, controller.updateSeekPreviewBy(-5_000))

        assertEquals(listOf(55_000L, 50_000L), engine.seekPositions)
    }

    @Test
    fun `a wheel step inside the settle window keeps the preview alive`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size

        assertEquals(55_000L, controller.updateSeekPreviewBy(-5_000))
        engine.reportFirstFrameReady()
        advanceTimeBy(200)
        assertEquals(50_000L, controller.updateSeekPreviewBy(-5_000))
        advanceTimeBy(200)

        // 窗口从最后一档起算：没有先落定再重新静音的音量跳变。
        assertEquals(listOf(true), engine.muteCalls)
        assertEquals(fadesBeforePreview, engine.fadeInDurations.size)
        assertEquals(listOf(55_000L, 50_000L), engine.seekPositions)

        advanceTimeBy(PlaybackController.WHEEL_SETTLE_MILLIS + 1)

        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(fadesBeforePreview + 1, engine.fadeInDurations.size)
        assertEquals(50_000L, engine.seekPositions.last())
        assertNull(controller.seekPreviewMillis.value)
    }

    @Test
    fun `a wheel step just before the settle window still extends it`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val gapMillis = PlaybackController.WHEEL_SETTLE_MILLIS - 100

        assertEquals(55_000L, controller.updateSeekPreviewBy(-5_000))
        engine.reportFirstFrameReady()
        advanceTimeBy(gapMillis)

        // 窗口内再滚一格只顺延，不先落定再重新静音。
        assertEquals(55_000L, controller.seekPreviewMillis.value)
        assertEquals(listOf(true), engine.muteCalls)

        assertEquals(50_000L, controller.updateSeekPreviewBy(-5_000))
        advanceTimeBy(gapMillis)
        assertEquals(50_000L, controller.seekPreviewMillis.value)
        assertEquals(listOf(true), engine.muteCalls)

        advanceTimeBy(PlaybackController.WHEEL_SETTLE_MILLIS + 1)
        // 两档只收尾一次：静音一次、恢复一次、落定一跳到最后一档。
        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(listOf(55_000L, 50_000L), engine.seekPositions)
        assertNull(controller.seekPreviewMillis.value)
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

    @Test
    fun `stopping restores the audio muted by a preview`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(70_000)

        controller.stop()

        assertEquals(listOf(true, false), engine.muteCalls)
    }


    @Test
    fun `收尾 seek 也占住在途槽位`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(70_000)
        controller.updateSeekPreview(90_000)
        controller.commitSeekPreview()
        assertEquals(listOf(70_000L, 90_000L), engine.seekPositions)

        // 收尾 seek 还没回执：紧接着的拖动只覆盖待发目标，引擎里不会排两个 seek。
        controller.updateSeekPreview(120_000)
        assertEquals(listOf(70_000L, 90_000L), engine.seekPositions)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 90_000L, 120_000L), engine.seekPositions)
    }

    @Test
    fun `在途 seek 回执丢失时按超时放开预览`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(70_000)
        controller.updateSeekPreview(90_000)
        assertEquals(listOf(70_000L), engine.seekPositions)

        advanceTimeBy(PlaybackController.PREVIEW_SEEK_TIMEOUT_MILLIS + 1)
        controller.updateSeekPreview(120_000)

        // 回执丢了也不能让画面停在第一帧：超时后按新目标重新下发。
        assertEquals(listOf(70_000L, 120_000L), engine.seekPositions)
    }

    @Test
    fun `回执与位置都不到时按超时放开进度`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(120_000)
        controller.commitSeekPreview()
        assertNull(controller.settledSeekMillis.value)

        advanceTimeBy(PlaybackController.SEEK_SETTLE_TIMEOUT_MILLIS + 1)

        // 兜底只放开跟随，不把进度改写回旧值。
        assertEquals(120_000L, controller.settledSeekMillis.value)
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportPositionChanged(120_500, 600_000)
        assertEquals(120_500L, controller.progress.value.positionMillis)
    }

    @Test
    fun `反向拖动的落定也按目标一侧判定到达`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(100_000, 600_000)
        controller.updateSeekPreview(80_000)
        controller.commitSeekPreview()

        // 单个样本（引擎下发 seek 时的乐观写回）不算到达。
        engine.reportPositionChanged(80_000, 600_000)
        assertNull(controller.settledSeekMillis.value)

        engine.reportPositionChanged(79_900, 600_000)
        assertEquals(80_000L, controller.settledSeekMillis.value)
    }

    @Test
    fun `切集后新的拖动落定仍要等回执`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(120_000)
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        assertEquals(120_000L, controller.settledSeekMillis.value)

        // 切到第二集：引擎的 seek 序号按加载归零，控制层的回执基线也要换代。
        controller.next()
        engine.reportMediaLoaded()
        engine.reportPositionChanged(10_000, 600_000)

        controller.updateSeekPreview(100_000)
        controller.commitSeekPreview()

        // 新一集的落定不能沿用上一集的序号直接算完成。
        assertNull(controller.settledSeekMillis.value)
        engine.reportFirstFrameReady()
        assertEquals(100_000L, controller.settledSeekMillis.value)
    }

    @Test
    fun `旧代际的迟到回执不能结束落定`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val previousToken = engine.currentLoadToken

        controller.next()
        engine.reportMediaLoaded()
        engine.reportPositionChanged(10_000, 600_000)
        controller.updateSeekPreview(100_000)
        controller.commitSeekPreview()

        engine.reportFirstFrameReady(loadToken = previousToken)
        assertNull(controller.settledSeekMillis.value)

        engine.reportFirstFrameReady()
        assertEquals(100_000L, controller.settledSeekMillis.value)
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
        open(controller)
        engine.reportMediaLoaded()
        return controller
    }

    private fun open(controller: PlaybackController) {
        val files = listOf(
            MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"),
            MediaFile(name = "ep2.mkv", location = "root/ep2.mkv"),
        )
        controller.openPlaylist(files, files.first())
    }
}
