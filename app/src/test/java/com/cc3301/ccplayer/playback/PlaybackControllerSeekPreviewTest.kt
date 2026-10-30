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

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        advanceTimeBy(PlaybackController.PREVIEW_HEARTBEAT_MILLIS)

        // 在途只留一个 seek；中途目标被最后一个覆盖，队列不随拖动增长。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(120_000L, controller.seekPreviewMillis.value)

        // 回执到达后只补发被覆盖的最后一个目标。
        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 120_000L), engine.seekPositions)
    }

    @Test
    fun `死区只由界面判定，控制层不吞显示目标`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        engine.reportFirstFrameReady()

        // 未越过像素死区：界面传 requestSeek = false，显示目标照样跟随。
        controller.updateSeekPreview(requestSeek = false, positionMillis = 70_400)
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(70_400L, controller.seekPreviewMillis.value)

        // 越过死区后界面置真，控制层照发。
        controller.updateSeekPreview(requestSeek = true, positionMillis = 71_400)
        assertEquals(listOf(70_000L, 71_400L), engine.seekPositions)
    }

    @Test
    fun `快拖后立刻松手不回跳`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        // 快速拖到 2:00 立刻松手：预览与落定是同一个目标。
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        assertEquals(120_000L, controller.progress.value.positionMillis)

        // 引擎下发 seek 时把位置乐观写成目标，随后迟到的旧位置样本才落盘。
        engine.reportPositionChanged(120_000, 600_000)
        engine.reportPositionChanged(80_000, 600_000)

        // 回执到达前进度停在目标上，圆球不回跳。
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportPositionChanged(120_040, 600_000)
        assertEquals(120_040L, controller.progress.value.positionMillis)
    }

    @Test
    fun `回执不到时连续两个越过目标的样本才算落定`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()

        // 单个样本可能是引擎下发 seek 时的乐观写回，不算到达。
        engine.reportPositionChanged(120_000, 600_000)
        assertNull(controller.seekSettlement.value?.targetMillis)

        engine.reportPositionChanged(120_010, 600_000)
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `preview seeks mute once and the release lands before restoring the audio`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 80_000)

        // 预览 seek 各自淡入会造成拖动中断续爆音：只静音一次，不逐次淡入。
        assertEquals(listOf(true), engine.muteCalls)
        assertEquals(fadesBeforePreview, engine.fadeInDurations.size)

        controller.commitSeekPreview()

        // 上一发仍在途：收尾挂起，声音与淡入一起等回执，旧位置不会以满音量播出。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(listOf(true), engine.muteCalls)

        engine.reportFirstFrameReady()

        assertEquals(
            listOf("seek(80000)", "muted(false)", "fade(500)"),
            engine.seekAudioCalls.takeLast(3),
        )
        assertEquals(listOf(70_000L, 80_000L), engine.seekPositions)
        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(fadesBeforePreview + 1, engine.fadeInDurations.size)
    }

    @Test
    fun `release lands the exact preview target after a covered drag`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 130_000)
        assertEquals(listOf(70_000L), engine.seekPositions)

        // 被覆盖掉的中间位置由收尾 seek 精确补上：上一发在途时先挂起。
        controller.commitSeekPreview()
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertNull(controller.seekPreviewMillis.value)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 130_000L), engine.seekPositions)
        assertEquals(130_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `releasing at the last preview target does not seek again`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
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

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
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
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)

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
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)

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
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)

        controller.seekTo(200_000)

        // 上一发在途：跳转挂起，回执后按先 seek、再恢复声音与淡入的顺序收尾。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertNull(controller.seekPreviewMillis.value)

        engine.reportFirstFrameReady()
        assertEquals(
            listOf("seek(200000)", "muted(false)", "fade(500)"),
            engine.seekAudioCalls.takeLast(3),
        )
        assertEquals(listOf(70_000L, 200_000L), engine.seekPositions)

        engine.reportPositionChanged(200_000, 600_000)
        engine.reportPositionChanged(200_010, 600_000)
        assertEquals(200_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `dragging during the first load does not preview`() = runTest {
        val controller = controller()
        open(controller)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 30_000)
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

        controller.updateSeekPreview(requestSeek = true, positionMillis = 30_000)

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

        controller.updateSeekPreview(requestSeek = true, positionMillis = 30_000)
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

        controller.updateSeekPreview(requestSeek = true, positionMillis = 30_000)

        assertNull(controller.seekPreviewMillis.value)
        assertTrue(engine.seekPositions.isEmpty())
        assertTrue(engine.muteCalls.isEmpty())
    }

    @Test
    fun `dragging with an unknown duration leaves no preview state`() = runTest {
        val controller = playingController()

        controller.updateSeekPreview(requestSeek = true, positionMillis = 30_000)

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

        controller.updateSeekPreview(requestSeek = true, positionMillis = 300_000)
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

        controller.updateSeekPreview(requestSeek = true, positionMillis = 599_000)
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
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        assertEquals(listOf(true), engine.muteCalls)

        controller.next()

        assertEquals(listOf(true, false), engine.muteCalls)
    }

    @Test
    fun `stopping restores the audio muted by a preview`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)

        controller.stop()

        assertEquals(listOf(true, false), engine.muteCalls)
    }


    @Test
    fun `上一发在途时收尾 seek 挂起到回执之后`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        controller.commitSeekPreview()
        // 上一发还没回执：收尾挂起，命令队列只有一发。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(90_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 90_000L), engine.seekPositions)
        // 挂起的收尾发出后仍要等它自己的回执或位置到达。
        assertNull(controller.seekSettlement.value)
        engine.reportPositionChanged(90_000, 600_000)
        engine.reportPositionChanged(90_010, 600_000)
        assertEquals(90_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `回执丢失时引擎待执行 seek 不增长`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        assertEquals(listOf(70_000L), engine.seekPositions)

        // 控制层按超时放开自己的槽位，引擎侧仍在途：新目标只覆盖待发目标，命令队列恒为 1 + 1。
        advanceTimeBy(PlaybackController.PREVIEW_SEEK_TIMEOUT_MILLIS + 1)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)

        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(120_000L, engine.pendingSeekMillis)

        // 上一发就绪后补发被覆盖的目标，队列不再增长。
        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 120_000L), engine.seekPositions)
        assertNull(engine.pendingSeekMillis)
    }

    @Test
    fun `回执与位置都不到时按超时放开进度`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        assertNull(controller.seekSettlement.value?.targetMillis)

        advanceTimeBy(PlaybackController.SEEK_SETTLE_TIMEOUT_MILLIS + 1)

        // 兜底只放开跟随，不发落定通知，也不把进度改写回旧值。
        assertNull(controller.seekSettlement.value)
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportPositionChanged(120_500, 600_000)
        assertEquals(120_500L, controller.progress.value.positionMillis)
    }

    @Test
    fun `反向拖动的落定也按目标一侧判定到达`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(100_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 80_000)
        controller.commitSeekPreview()

        // 单个样本（引擎下发 seek 时的乐观写回）不算到达。
        engine.reportPositionChanged(80_000, 600_000)
        assertNull(controller.seekSettlement.value?.targetMillis)

        engine.reportPositionChanged(79_900, 600_000)
        assertEquals(80_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `切集后新的拖动落定仍要等回执`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
        val previousSettlementId = controller.seekSettlement.value!!.id

        // 切到第二集：引擎的 seek 序号按加载归零，控制层的回执基线也要换代。
        controller.next()
        engine.reportMediaLoaded()
        engine.reportPositionChanged(10_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 100_000)
        controller.commitSeekPreview()

        // 新一集的落定不能沿用上一集的序号直接算完成：通知 id 不变。
        assertEquals(previousSettlementId, controller.seekSettlement.value!!.id)
        engine.reportFirstFrameReady()
        assertEquals(100_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `旧代际的迟到回执不能结束落定`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val previousToken = engine.currentLoadToken

        controller.next()
        engine.reportMediaLoaded()
        engine.reportPositionChanged(10_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 100_000)
        controller.commitSeekPreview()

        engine.reportFirstFrameReady(loadToken = previousToken)
        assertNull(controller.seekSettlement.value?.targetMillis)

        engine.reportFirstFrameReady()
        assertEquals(100_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `停手不动再松手不重发 seek，落定仍要等回执`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        engine.reportFirstFrameReady()
        // 手指停在死区内：显示目标继续跟随真实位置，但不再下发。
        controller.updateSeekPreview(requestSeek = false, positionMillis = 120_400)
        assertEquals(listOf(120_000L), engine.seekPositions)

        controller.commitSeekPreview()
        // 目标与最后一次下发相差不足死区：复用，不再补一次收尾 seek。
        assertEquals(listOf(120_000L), engine.seekPositions)

        // 落定仍要等回执或连续两个越过目标的样本。
        assertNull(controller.seekSettlement.value)
        engine.reportPositionChanged(120_400, 600_000)
        assertNull(controller.seekSettlement.value)
        engine.reportPositionChanged(120_410, 600_000)
        assertEquals(120_400L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `落定目标就是手指最后的位置`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        engine.reportFirstFrameReady()
        controller.updateSeekPreview(requestSeek = false, positionMillis = 120_500)
        controller.commitSeekPreview()

        // 死区不参与落点：进度停在手指当前位置，与它相差 0。
        assertEquals(120_500L, controller.progress.value.positionMillis)
    }

    @Test
    fun `同一目标连续两次拖到端点各结算一次`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 600_000)
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        val first = controller.seekSettlement.value ?: error("第一次落定没有通知")

        controller.updateSeekPreview(requestSeek = true, positionMillis = 600_000)
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        val second = controller.seekSettlement.value ?: error("第二次落定没有通知")

        // 同一目标两次落定各自发通知：界面只有看到更大的 id 才撤掉新的保持值。
        assertEquals(600_000L, first.targetMillis)
        assertEquals(600_000L, second.targetMillis)
        assertTrue(second.id > first.id)
    }

    @Test
    fun `拖到片尾仍在预览时播完会收起预览并恢复静音`() = runTest {
        val controller = singleController()
        engine.reportPositionChanged(500_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 600_000)
        assertEquals(600_000L, controller.seekPreviewMillis.value)
        assertEquals(listOf(true), engine.muteCalls)

        // 仍在预览态时播完：收起预览、恢复静音，进度不被拖动目标钉住。
        engine.reportPlaybackEnded()

        assertNull(controller.seekPreviewMillis.value)
        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(500_000L, controller.progress.value.positionMillis)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    private fun TestScope.controller(): PlaybackController = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        frameProbeDispatcher = UnconfinedTestDispatcher(testScheduler),
        nowMillis = { testScheduler.currentTime },
    )

    /** 起播单集并等首帧就绪：播完走会话结束，不触发自动连播。 */
    private fun TestScope.singleController(): PlaybackController {
        val controller = controller()
        val files = listOf(MediaFile(name = "ep1.mkv", location = "root/ep1.mkv"))
        controller.openPlaylist(files, files.first())
        engine.reportMediaLoaded()
        return controller
    }

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
