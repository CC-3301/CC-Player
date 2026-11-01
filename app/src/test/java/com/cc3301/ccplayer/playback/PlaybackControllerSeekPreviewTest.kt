package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import com.cc3301.ccplayer.progress.MediaProgress
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
    fun `快速连续拖动只执行最后一个目标`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        advanceTimeBy(PlaybackController.PREVIEW_HEARTBEAT_MILLIS)

        // 在途那一跳被新目标就地改写：中途目标不排队，队列里只剩当前最后目标。
        assertNull(engine.pendingSeekMillis)
        assertEquals(120_000L, controller.seekPreviewMillis.value)

        // 只有最后目标出帧，中途目标不再各执行一次。
        engine.reportFirstFrameReady()
        assertEquals(listOf(120_000L), engine.seekExecutions.map { it.second })
    }

    @Test
    fun `快速拖动松手不产生额外 seek`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        // 松手前只有这两条真实下发：中途目标不排队，队列里只剩当前最后目标。
        assertEquals(listOf(70_000L, 120_000L), engine.seekPositions)

        controller.commitSeekPreview()

        // 落定目标就是最后一次下发的那一跳：不补发，也不复用被替换掉的旧目标。
        assertEquals(listOf(70_000L, 120_000L), engine.seekPositions)
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `预览改写后旧序号的 ready 不结算新目标`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        // 改写会换新序号：两次下发各占一条。
        val staleSerial = engine.issuedSeekSerials.first()
        val currentSerial = engine.issuedSeekSerials.last()
        assertTrue(currentSerial > staleSerial)

        controller.commitSeekPreview()

        // 旧目标那条命令的 ready 迟到：序号对不上当前这一跳，不结算落定。
        engine.reportReadyForSerial(staleSerial)
        assertNull(controller.seekSettlement.value)
        assertEquals(listOf(true), engine.muteCalls)

        // 当前序号的那一条才结算，落点是手指目标。
        engine.reportReadyForSerial(currentSerial)
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
        assertEquals(listOf(true, false), engine.muteCalls)
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
    fun `旧到达窗口两侧的位置样本都不结算落定`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()

        // 旧判据的窗口下沿（目标 −50ms）与上沿（目标 +1500ms）：位置样本本身不撤掉落定。
        engine.reportPositionChanged(119_950, 600_000)
        engine.reportPositionChanged(121_500, 600_000)
        assertNull(controller.seekSettlement.value)
        assertEquals(120_000L, controller.progress.value.positionMillis)

        engine.reportSeekExecuted()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `反向跳转落到目标 −278ms 仍按回执结算`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(90_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 45_000)
        controller.commitSeekPreview()

        // 解码器从目标前的关键帧起解：位置先落到目标 −278ms，这是固有位移，不是「未到达」。
        engine.reportPositionChanged(44_722, 600_000)
        assertNull(controller.seekSettlement.value)
        assertEquals(45_000L, controller.progress.value.positionMillis)

        engine.reportSeekExecuted()
        assertEquals(45_000L, controller.seekSettlement.value?.targetMillis)
        // 结算后跟随引擎值，固有位移不被改写。
        engine.reportPositionChanged(45_010, 600_000)
        assertEquals(45_010L, controller.progress.value.positionMillis)
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
        // 被覆盖掉的中间目标不再补发：在途那一跳已就地改成最后目标。
        assertEquals(listOf(70_000L, 130_000L), engine.seekPositions)

        controller.commitSeekPreview()
        assertEquals(listOf(70_000L, 130_000L), engine.seekPositions)
        assertNull(controller.seekPreviewMillis.value)
        assertEquals(130_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 130_000L), engine.seekPositions)
        assertEquals(130_000L, controller.progress.value.positionMillis)
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
    fun `首次加载文件还没打开时跳转不下发 seek`() = runTest {
        val controller = controller()
        open(controller)

        controller.seekTo(200_000)

        // 目标文件还没打开：下发会打到旧文件上，进度也不跳。
        assertTrue(engine.seekPositions.isEmpty())
        assertNull(controller.seekSettlement.value)
        assertEquals(0L, controller.progress.value.positionMillis)
    }

    @Test
    fun `首次加载文件已打开后跳转照常下发`() = runTest {
        val controller = controller()
        open(controller)
        engine.reportFileLoaded()

        controller.seekTo(200_000)

        assertEquals(listOf(200_000L), engine.seekPositions)
        assertEquals(200_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(200_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `切集期跳转照常下发，收尾仍不下发`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.next()
        assertTrue(controller.switching.value)

        controller.seekTo(200_000)
        controller.commitSeekPreview()

        assertEquals(listOf(200_000L), engine.seekPositions)
        assertTrue(engine.muteCalls.isEmpty())
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
    fun `上一发在途时收尾目标就地替换，不补发也不重放旧目标`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        controller.commitSeekPreview()
        // 上一发还没回执：收尾目标就地改写那一跳，队列里没有待执行。
        assertEquals(listOf(70_000L, 90_000L), engine.seekPositions)
        assertNull(engine.pendingSeekMillis)
        assertEquals(90_000L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(listOf(70_000L, 90_000L), engine.seekPositions)
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

        // 引擎在途超时：视为这一跳已执行，回报带的是这一跳自己的身份与改写后的目标。
        engine.reportSeekExecuted()
        assertEquals(listOf(70_000L, 120_000L), engine.seekPositions)
        assertEquals(listOf(1L to 120_000L), engine.seekExecutions)
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
    fun `预留的中途目标被替换，只补发最后目标`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 70_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 90_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 130_000)
        controller.commitSeekPreview()

        // 队列里只剩当前最后目标：中途目标既不进待执行，也不会各执行一次。
        assertNull(engine.pendingSeekMillis)
        assertEquals(listOf(70_000L, 90_000L, 130_000L), engine.seekPositions)

        engine.reportSeekExecuted()
        assertEquals(listOf(1L to 130_000L), engine.seekExecutions)
        assertEquals(130_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `同一目标连续两次落定各通知一次，id 递增`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 500_000)
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        val first = controller.seekSettlement.value ?: error("第一次落定没有通知")

        controller.updateSeekPreview(requestSeek = true, positionMillis = 500_000)
        controller.commitSeekPreview()
        engine.reportFirstFrameReady()
        val second = controller.seekSettlement.value ?: error("第二次落定没有通知")

        // 同一目标两次落定各自发通知：界面只有看到更大的 id 才撤掉新的保持值。
        assertEquals(500_000L, first.targetMillis)
        assertEquals(500_000L, second.targetMillis)
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
    fun `停手不动再松手时目标不同必多一跳，落点等于手指目标`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        engine.reportFirstFrameReady()
        // 手指停在像素死区内：显示目标继续跟随真实位置，但不再下发。
        controller.updateSeekPreview(requestSeek = false, positionMillis = 120_400)
        assertEquals(listOf(120_000L), engine.seekPositions)

        controller.commitSeekPreview()
        // 目标不同必补一跳：落定不允许停在旧目标上。
        assertEquals(listOf(120_000L, 120_400L), engine.seekPositions)
        assertEquals(120_400L, controller.progress.value.positionMillis)

        engine.reportFirstFrameReady()
        assertEquals(120_400L, controller.seekSettlement.value?.targetMillis)
        assertEquals(120_400L, controller.progress.value.positionMillis)
    }

    @Test
    fun `落定目标就是手指最后的位置`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        engine.reportFirstFrameReady()
        controller.updateSeekPreview(requestSeek = false, positionMillis = 120_500)
        controller.commitSeekPreview()
        assertEquals(listOf(120_000L, 120_500L), engine.seekPositions)

        // 死区不参与落点：进度停在手指当前位置，落定等补发的那一跳回报。
        assertEquals(120_500L, controller.progress.value.positionMillis)
        engine.reportFirstFrameReady()
        assertEquals(120_500L, controller.seekSettlement.value?.targetMillis)
        assertEquals(120_500L, controller.progress.value.positionMillis)
    }

    @Test
    fun `目标完全相同时只下发一跳并当场结算`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        engine.reportFirstFrameReady()

        // 同一目标收尾复用已执行的那一跳：不再下发，落定当场结算。
        controller.commitSeekPreview()
        assertEquals(listOf(120_000L), engine.seekPositions)
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `续播查询未完成时用户先跳，查询完成后不再补发续播 seek`() = runTest {
        val controller = playingController()
        progress.records["root/ep2.mkv"] = MediaProgress("root/ep2.mkv", 20_000, 600_000, 0)
        progress.holdFinds()

        controller.next()
        engine.reportFileLoaded()
        // 查询还挂着：用户先跳走。
        controller.seekTo(300_000)
        assertEquals(listOf(300_000L), engine.seekPositions)

        progress.releaseFinds()
        runCurrent()

        // 查询完成后不得再下发续播 seek 把用户拉回旧位置。
        assertEquals(listOf(300_000L), engine.seekPositions)
        assertEquals(300_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `预览中收到播完不切集也不结束会话`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        val startedLoads = engine.startedUrls.size

        // 预览 seek 期间的播完不算连播信号：不切集、不结束会话、不收起预览。
        engine.reportPlaybackEnded()

        assertEquals(0, controller.currentIndex.value)
        assertEquals(startedLoads, engine.startedUrls.size)
        assertFalse(controller.switching.value)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(120_000L, controller.seekPreviewMillis.value)
        assertEquals(listOf(true), engine.muteCalls)
    }

    @Test
    fun `落定在途收到播完不切集`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        val startedLoads = engine.startedUrls.size

        // 收尾已下发、回报还没到：此时播完不能当连播信号。
        assertNull(controller.seekPreviewMillis.value)
        engine.reportPlaybackEnded()

        assertEquals(0, controller.currentIndex.value)
        assertEquals(startedLoads, engine.startedUrls.size)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 落定执行后照常结算，播放停在落定目标上。
        engine.reportFirstFrameReady()
        assertEquals(120_000L, controller.seekSettlement.value?.targetMillis)
    }

    @Test
    fun `普通 seek 落到片尾仍触发自动连播`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)

        // 通知栏或手动跳转不是预览：它的落定在途不能拦住播完判定。
        controller.seekTo(599_000)
        assertNull(controller.seekPreviewMillis.value)

        engine.reportPlaybackEnded()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `预览落定期间引擎回的暂停不压成暂停态`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 598_000)
        controller.commitSeekPreview()
        val startedLoads = engine.startedUrls.size

        // keep-open 播完后引擎先报 eof，再回一条暂停；这条暂停不是用户意图。
        engine.reportPlaybackEnded()
        engine.reportPauseChanged(true)

        assertEquals(0, controller.currentIndex.value)
        assertEquals(startedLoads, engine.startedUrls.size)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 落定执行完按播放意图恢复：原本在播放就继续播。
        engine.reportSeekExecuted()
        assertEquals(1, engine.resumeCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `放弃落定后普通 seek 落定按播放意图恢复一次`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()

        // keep-open 播完后引擎回的暂停在落定在途时被吞掉。
        engine.reportPauseChanged(true)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 新拖动丢弃这次落定；吞掉的回声留到普通落定结算时按播放意图收场。
        controller.updateSeekPreview(requestSeek = false, positionMillis = 200_000)
        controller.seekTo(300_000)
        engine.reportSeekExecuted()
        engine.reportSeekExecuted()

        // 播放意图仍是播放：结算恢复一次，迟到回执不重复恢复。
        assertEquals(1, engine.resumeCalls)
        engine.reportSeekExecuted()
        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `预览落定吞过回声后普通 seek 落定恢复播放一次`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()

        // 预览落定在途时吞掉 keep-open 回声。
        engine.reportPauseChanged(true)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 用户直接发普通 seek：它接管落定，吞掉的回声交给结算按播放意图处理。
        controller.seekTo(300_000)
        engine.reportSeekExecuted()
        engine.reportSeekExecuted()

        assertEquals(1, engine.resumeCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
        // 迟到的回执不会重复恢复。
        engine.reportSeekExecuted()
        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `预览落定在途按暂停立刻生效，结算后保持暂停`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()

        // 用户按暂停：不等引擎回声，状态立刻变。
        controller.pause()
        assertEquals(PlaybackState.Paused, controller.state.value)

        // keep-open 回声在落定在途时到达：吞掉，但不改用户的暂停意图。
        engine.reportPauseChanged(true)
        assertEquals(PlaybackState.Paused, controller.state.value)

        // 落定结算按暂停意图收尾：不恢复播放，也不把状态改回播放。
        engine.reportSeekExecuted()
        assertEquals(0, engine.resumeCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `落定被取消收尾时结算吞掉的暂停回声`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        controller.updateSeekPreview(requestSeek = true, positionMillis = 120_000)
        controller.commitSeekPreview()
        assertEquals(listOf(true), engine.muteCalls)

        // 落定在途吞掉 keep-open 回声：引擎实际停了，状态仍按播放意图保持播放。
        engine.reportPauseChanged(true)
        assertEquals(PlaybackState.Playing, controller.state.value)

        // 再次拖动后由空闲超时按 commit=false 收尾：这一次收尾要结算吞掉的回声。
        controller.updateSeekPreview(requestSeek = false, positionMillis = 200_000)
        advanceTimeBy(PlaybackController.PREVIEW_IDLE_TIMEOUT_MILLIS + 1)
        runCurrent()

        // 不残留静音，也不留下“引擎已暂停、状态还在播放”。
        assertEquals(listOf(true, false), engine.muteCalls)
        assertEquals(1, engine.resumeCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `预览目标落在末尾时收进安全余量`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(60_000, 600_000)
        val lastSafePosition = 600_000L - PlaybackController.PREVIEW_END_MARGIN_MILLIS

        controller.updateSeekPreview(requestSeek = true, positionMillis = 600_000)

        // 显示目标仍是手指位置；下发与落定的 seek 离片尾留出安全余量，不把文件推到片尾。
        assertEquals(600_000L, controller.seekPreviewMillis.value)
        assertEquals(listOf(lastSafePosition), engine.seekPositions)

        controller.commitSeekPreview()
        assertEquals(listOf(lastSafePosition), engine.seekPositions)
        assertEquals(lastSafePosition, controller.progress.value.positionMillis)
        assertFalse(controller.switching.value)
        assertEquals(0, controller.currentIndex.value)

        // 夹取后的位置不在「播完」带内：退出预览后按正常位置落库，不按看过一遍清掉。
        engine.reportFirstFrameReady()
        runCurrent()
        engine.reportPositionChanged(lastSafePosition, 600_000)
        runCurrent()
        assertEquals(lastSafePosition, progress.records["root/ep1.mkv"]?.positionMillis)
    }

    @Test
    fun `时长不超过余量时预览仍有可拖动区间`() = runTest {
        val controller = playingController()
        engine.reportPositionChanged(0, 1_500)
        val limit = 1_500L / 2

        controller.updateSeekPreview(requestSeek = true, positionMillis = 1_500)

        // 短片余量按半程缩：拖动不会被压成 0，仍有一段可预览区间。
        assertEquals(1_500L, controller.seekPreviewMillis.value)
        assertEquals(listOf(limit), engine.seekPositions)
        assertTrue(limit > 0L)
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

        assertEquals(listOf(120_000L, 180_000L, 120_000L), engine.seekPositions)
        assertTrue(engine.currentSeekId > firstSeekId)
        // 第一跳已经执行完，但落定认的是新的一跳，不能拿旧回报当场结算。
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
