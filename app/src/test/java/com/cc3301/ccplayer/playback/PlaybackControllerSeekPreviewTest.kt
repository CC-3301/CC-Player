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
 * 拖动与滚轮的实时画面预览：节流下发真实 seek、预览期间静音、收尾按引擎执行回报落定，
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

        // 回报到达前进度停在目标上，圆球不回跳。
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportPositionChanged(120_040, 600_000)
        assertEquals(120_040L, controller.progress.value.positionMillis)
    }

    @Test
    fun `落定只认引擎回报的执行目标，位置样本不参与`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()

        // 位置样本恰好等于目标也不算落定：到达由播放核心回报执行，不由位置推断。
        engine.reportPositionChanged(120_000, 600_000)
        engine.reportPositionChanged(120_010, 600_000)
        assertNull(controller.seekSettlement.value)

        engine.reportFirstFrameReady()
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

        // 收尾目标与最后一次下发相同：复用那一跳，声音等它执行完再恢复。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(listOf(true), engine.muteCalls)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 80_000L), engine.seekPositions)
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

        // 被覆盖掉的中间位置由收尾目标补齐：它已在引擎的待执行槽里，不重复下发。
        controller.commitSeekPreview()
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertNull(controller.seekPreviewMillis.value)
        assertEquals(130_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 130_000L), engine.seekPositions)
        assertEquals(130_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(130_000L, controller.seekSettlement.value?.targetMillis)
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
        assertEquals(listOf(true), engine.muteCalls)

        engine.reportFirstFrameReady()
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
        // 落定之后进度恢复更新，不再被预览吞掉。
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
        assertNull(controller.seekSettlement.value)

        engine.reportPositionChanged(120_000, 600_000)
        assertEquals(120_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `an external seek lands before restoring the audio`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)

        controller.seekTo(200_000)

        // 上一跳在途：跳转进待执行槽，回执后按顺序补发。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(200_000L, engine.pendingSeekMillis)
        assertNull(controller.seekPreviewMillis.value)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 200_000L), engine.seekPositions)

        engine.reportFirstFrameReady()
        assertEquals(
            listOf("seek(200000)", "muted(false)", "fade(500)"),
            engine.seekAudioCalls.takeLast(3),
        )
        assertEquals(200_000L, controller.seekSettlement.value?.targetMillis)
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
        // 最后一跳的回报先到：收尾时才能当场结算。
        engine.reportFirstFrameReady()
        advanceTimeBy(200)

        // 窗口从最后一档起算：没有先落定再重新静音的音量跳变。
        assertEquals(listOf(true), engine.muteCalls)
        assertEquals(fadesBeforePreview, engine.fadeInDurations.size)
        assertEquals(listOf(55_000L, 50_000L), engine.seekPositions)

        advanceTimeBy(PlaybackController.WHEEL_SETTLE_MILLIS + 1)

        // 最后一跳已回报执行：落定当场结算，声音恢复并淡入。
        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(fadesBeforePreview + 1, engine.fadeInDurations.size)
        assertEquals(50_000L, engine.seekPositions.last())
        assertEquals(50_000L, controller.seekSettlement.value?.targetMillis)
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
        engine.reportFirstFrameReady()
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
    fun `上一发在途时收尾目标进待执行槽，回执后只补发一次`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        controller.commitSeekPreview()
        // 上一发还没回执：收尾目标替换待执行，命令队列只有一发。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(90_000L, engine.pendingSeekMillis)
        assertEquals(90_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 90_000L), engine.seekPositions)
        // 补发的收尾仍要等它自己的执行回报。
        assertNull(controller.seekSettlement.value)

        engine.reportFirstFrameReady()
        assertEquals(90_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `回执一直不来时引擎超时回报，落定不会被永久钉住`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        assertEquals(listOf(true), engine.muteCalls)

        // 引擎在途超时：视为这一跳已执行并补发待执行目标，回报带的是那一跳自己的身份。
        engine.reportSeekExecuted()
        assertEquals(listOf(70_000L, 120_000L), engine.seekPositions)
        assertEquals(listOf(1L to 70_000L), engine.seekExecutions)
        assertNull(controller.seekSettlement.value)

        engine.reportSeekExecuted()
        assertEquals(listOf(1L to 70_000L, 2L to 120_000L), engine.seekExecutions)
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
        assertEquals(listOf(true, false), engine.muteCalls)
        assertNull(engine.pendingSeekMillis)
    }

    @Test
    fun `松手后退后台两次收尾只下发一次 seek、只落定一次`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        // 退后台再走一次收尾：不重复下发，也不重复通知。
        controller.pauseForBackground()
        assertEquals(listOf(120_000L), engine.seekPositions)

        engine.reportFirstFrameReady()
        val settled = controller.seekSettlement.value ?: error("松手收尾没有落定")
        assertEquals(120_000L, settled.targetMillis)

        controller.pauseForBackground()
        engine.reportFirstFrameReady()
        assertEquals(listOf(120_000L), engine.seekPositions)
        assertEquals(settled.id, controller.seekSettlement.value?.id)
        assertEquals(listOf(true, false), engine.muteCalls)
    }

    @Test
    fun `按住时被中断会恢复静音且不跨集泄漏`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        assertEquals(listOf(true), engine.muteCalls)

        // 来电/锁屏让播放页退后台：预览收尾，但这一跳还没执行，声音先不恢复。
        controller.pauseForBackground()
        assertEquals(listOf(true), engine.muteCalls)

        // 播放核心回报这一跳已执行：声音恢复。
        engine.reportSeekExecuted()
        assertEquals(listOf(true, false), engine.muteCalls)

        // 切集不会把静音带进新一集，也不会再写一次静音。
        controller.next()
        engine.reportMediaLoaded()
        assertEquals(listOf(true, false), engine.muteCalls)
    }

    @Test
    fun `引擎在途时再来预览与收尾，待执行只保留最后一个目标`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 130_000)
        controller.commitSeekPreview()

        // 队列恒为「1 执行 + 1 待执行」：待执行只剩最后一个目标。
        assertEquals(listOf(70_000L), engine.seekPositions)
        assertEquals(130_000L, engine.pendingSeekMillis)

        engine.reportSeekExecuted()
        assertEquals(listOf(70_000L, 130_000L), engine.seekPositions)
        assertNull(engine.pendingSeekMillis)
        assertNull(controller.seekSettlement.value)

        // 补发的最后一跳也要等它自己的执行回报。
        engine.reportSeekExecuted()
        assertEquals(130_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `同一目标连续两次落定各通知一次，id 递增`() = runTest {
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
    fun `跨集后落定只认新代自己下发的 seek`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        val previous = controller.seekSettlement.value ?: error("第一次落定没有通知")

        // 切到第二集：引擎作废上一代在途与待执行的 seek，落定只认新代自己那一跳。
        controller.next()
        engine.reportMediaLoaded()
        engine.reportPositionChanged(10_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 100_000)
        controller.commitSeekPreview()

        assertEquals(previous.id, controller.seekSettlement.value?.id)
        engine.reportFirstFrameReady()
        val settled = controller.seekSettlement.value ?: error("切集后没有落定")
        assertEquals(100_000L, settled.targetMillis)
        assertTrue(settled.id > previous.id)
    }

    @Test
    fun `停手不动再松手不重发 seek，落定仍等引擎执行回报`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        // 手指停在死区内：显示目标继续跟随真实位置，但不再下发。
        controller.updateSeekPreview(requestSeek = false, positionMillis = 120_400)
        assertEquals(listOf(120_000L), engine.seekPositions)

        controller.commitSeekPreview()
        // 目标与最后一次下发相差不足死区：复用，不再补一次收尾 seek。
        assertEquals(listOf(120_000L), engine.seekPositions)
        assertNull(controller.seekSettlement.value)

        engine.reportFirstFrameReady()
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
        assertEquals(120_500L, controller.seekSettlement.value?.targetMillis)
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

    @Test
    fun `拖回同一毫秒重新下发时，旧回报不结算新的一跳`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        // 第一次拖到 2:00 并让它执行完。
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        engine.reportSeekExecuted()
        val firstSeekId = engine.seekExecutions.single().first

        // 再拖到 3:00（在途），随后拖回 2:00 松手：目标毫秒与第一次相同，但是新的一跳。
        controller.updateSeekPreview(requestSeek = true, positionMillis = 180_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()

        assertEquals(listOf(120_000L, 180_000L), engine.seekPositions)
        assertTrue(engine.currentSeekId > firstSeekId)
        // 第一跳已经执行完，但落定认的是新的一跳，不能拿旧回报当场结算。
        assertNull(controller.seekSettlement.value)

        engine.reportSeekExecuted()
        assertNull(controller.seekSettlement.value)

        engine.reportSeekExecuted()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `不是本代那一跳的回执既不结算也不改写执行身份`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        engine.reportSeekExecuted()

        // 旧代或旧跳的迟到回执：既不是当前预览那一跳，也不是等待中的落定。
        engine.reportSeekExecuted(seekId = 99, targetMillis = 500_000)
        assertNull(controller.seekSettlement.value)

        // 复用的那一跳此前已回报过执行：落定当场结算，说明迟到回执没有改写当前代的执行身份。
        controller.commitSeekPreview()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
        assertEquals(listOf(true, false), engine.muteCalls)
    }

    @Test
    fun `切集后到达的旧代回执不结算新代的落定`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        val oldSeekId = engine.currentSeekId

        // 切集插在在途与下发之间：上一代的目标作废，新一集重新拖一次。
        controller.next()
        engine.reportMediaLoaded()
        engine.reportPositionChanged(10_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        controller.commitSeekPreview()

        // 上一代的迟到回执不得结算这一代的落定。
        engine.reportSeekExecuted(seekId = oldSeekId, targetMillis = 120_000)
        assertNull(controller.seekSettlement.value)

        engine.reportSeekExecuted()
        assertEquals(90_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `预览中播放失败会恢复静音，之后的收尾不再重复写`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size
        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        assertEquals(listOf(true), engine.muteCalls)

        // 播放中出错：会话结束，声音必须恢复，不能留在静音。
        engine.reportPlaybackFailed()

        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(PlaybackState.Ended, controller.state.value)
        assertEquals(fadesBeforePreview, engine.fadeInDurations.size)

        // 结束后的收尾走同一条出口：不重复恢复，也不落定。
        controller.commitSeekPreview()
        assertEquals(listOf(true, false), engine.muteCalls)
        assertNull(controller.seekSettlement.value)
    }

    @Test
    fun `旧跳的回执迟到时不结算已下发的新一跳`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        // 第一跳执行完，记下它的身份。
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        engine.reportSeekExecuted()
        val oldSeekId = engine.seekExecutions.single().first

        // 新的一跳已下发，落定正在等它自己的回执。
        controller.updateSeekPreview(requestSeek = true, positionMillis = 240_000)
        controller.commitSeekPreview()

        // 旧跳的回执这时才到：不得结算新一跳的落定。
        engine.reportSeekExecuted(seekId = oldSeekId, targetMillis = 120_000)
        assertNull(controller.seekSettlement.value)
        assertEquals(listOf(true), engine.muteCalls)

        engine.reportSeekExecuted()
        assertEquals(240_000L, controller.seekSettlement.value?.targetMillis)
        assertEquals(listOf(true, false), engine.muteCalls)
    }

    @Test
    fun `落定等回执期间静音保持，结算时只淡入一次`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val fadesBeforePreview = engine.fadeInDurations.size
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        assertEquals(listOf(true), engine.muteCalls)

        // 落定还在等回执：再收尾一次没有新落点，不能提前恢复静音让旧位置满音量播出。
        controller.commitSeekPreview()
        assertEquals(listOf(true), engine.muteCalls)
        assertEquals(fadesBeforePreview, engine.fadeInDurations.size)
        assertNull(controller.seekSettlement.value)

        engine.reportFirstFrameReady()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
        // 结算才恢复静音并淡入，各一次。
        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(fadesBeforePreview + 1, engine.fadeInDurations.size)
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
