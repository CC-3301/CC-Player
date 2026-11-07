package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 入口阶段状态机：只有一个阶段值，准备结果按代际严格相等判定。 */
class PlaybackEntryStateTest {

    private val state = PlaybackEntryState()

    /** 视频打开走到已接手：准备中 -> 等待接手 -> 已接手。 */
    private fun acceptedEntry(): Int {
        val generation = state.begin(preparing = true)
        state.prepared(generation)
        state.accepted(generation)
        return generation
    }

    @Test
    fun `视频打开先进入准备中`() {
        state.begin(preparing = true)

        assertEquals(EntryPhase.Preparing, state.phase)
        assertTrue(state.preparingUi)
        assertTrue(state.initialLoading(PlaybackState.Idle, switching = false))
    }

    @Test
    fun `音频打开同样从准备中开始但不显示准备期界面`() {
        val generation = state.begin(preparing = false)

        assertEquals(EntryPhase.Preparing, state.phase)
        assertFalse(state.preparingUi)
        assertTrue(state.isCurrent(generation))
    }

    @Test
    fun `准备完成后进入等待接手`() {
        val generation = state.begin(preparing = true)

        assertTrue(state.prepared(generation))

        assertEquals(EntryPhase.AwaitingHandover, state.phase)
        assertTrue(state.preparingUi)
    }

    @Test
    fun `接手后恢复界面`() {
        acceptedEntry()

        assertEquals(EntryPhase.Accepted, state.phase)
        assertFalse(state.preparingUi)
        assertTrue(state.initialLoading(PlaybackState.Loading, switching = false))
    }

    @Test
    fun `接手后首次就绪结束入口阶段`() {
        acceptedEntry()

        state.onPlaybackUpdate(PlaybackState.Playing, switching = false)

        assertNull(state.phase)
        assertFalse(state.initialLoading(PlaybackState.Playing, switching = false))
    }

    @Test
    fun `接手后转入切集结束入口阶段`() {
        acceptedEntry()

        state.onPlaybackUpdate(PlaybackState.Loading, switching = true)

        assertNull(state.phase)
    }

    @Test
    fun `接手的打开在已结束时报终态出口`() {
        acceptedEntry()

        state.onPlaybackUpdate(PlaybackState.Ended, switching = false)

        assertNull(state.phase)
    }

    @Test
    fun `接手的打开在空闲时报终态出口`() {
        acceptedEntry()

        state.onPlaybackUpdate(PlaybackState.Idle, switching = false)

        assertNull(state.phase)
    }

    @Test
    fun `准备期不受上一会话的就绪上报影响`() {
        state.begin(preparing = true)

        state.onPlaybackUpdate(PlaybackState.Playing, switching = false)

        assertEquals(EntryPhase.Preparing, state.phase)
        assertTrue(state.preparingUi)
    }

    @Test
    fun `等待接手不受上一会话的就绪上报影响`() {
        val generation = state.begin(preparing = true)
        state.prepared(generation)

        state.onPlaybackUpdate(PlaybackState.Paused, switching = false)

        assertEquals(EntryPhase.AwaitingHandover, state.phase)
    }

    @Test
    fun `准备期不计等待接手兜底`() {
        state.begin(preparing = true)

        state.onHandoverTick(nowMillis = 1_000, foreground = true)
        assertFalse(state.onHandoverTick(nowMillis = 60_000, foreground = true))
    }

    @Test
    fun `已接手后不再按等待接手兜底`() {
        acceptedEntry()

        state.onHandoverTick(nowMillis = 1_000, foreground = true)
        assertFalse(state.onHandoverTick(nowMillis = 60_000, foreground = true))
        assertFalse(state.failAwaitingHandover())
    }

    @Test
    fun `等待接手按实际时间差累计且只算前台`() {
        val generation = state.begin(preparing = true)
        state.prepared(generation)

        // 第一次 tick 只建立基线。
        state.onHandoverTick(nowMillis = 1_000, foreground = true)
        // 退后台：基线清掉，后台这段不算。
        state.onHandoverTick(nowMillis = 2_000, foreground = false)
        state.onHandoverTick(nowMillis = 30_000, foreground = true)
        assertFalse("回前台首 tick 只重建基线", state.onHandoverTick(nowMillis = 31_000, foreground = true))
        assertFalse(state.onHandoverTick(nowMillis = 34_500, foreground = true))
        assertTrue(state.onHandoverTick(nowMillis = 36_000, foreground = true))
    }

    @Test
    fun `暂停计时后不把暂停期间算进前台时长`() {
        val generation = state.begin(preparing = true)
        state.prepared(generation)
        state.onHandoverTick(nowMillis = 1_000, foreground = true)

        state.pauseHandoverTimer()
        state.onHandoverTick(nowMillis = 40_000, foreground = true)

        assertFalse(state.onHandoverTick(nowMillis = 42_000, foreground = true))
    }

    @Test
    fun `兜底到点按失败收尾并回滚`() {
        val generation = state.begin(preparing = true)
        state.prepared(generation)
        state.onHandoverTick(nowMillis = 1_000, foreground = true)
        assertTrue(state.onHandoverTick(nowMillis = 6_000, foreground = true))

        assertTrue(state.failAwaitingHandover())
        assertEquals(EntryPhase.Failed, state.phase)
        // 兜底到点：既有失败提示 + 有旧会话且不在片尾时撤回；没有旧会话时结束播放。
        val rollback = handoverTimeoutOutcome(PlaybackState.Playing, atEpisodeEnd = false)
        assertEquals(HANDOVER_TIMEOUT_FAILURE, rollback.failure)
        assertEquals(FailedEntryExit.LeavePlayerPage, rollback.exit)
        assertEquals(
            FailedEntryExit.StopPlayback,
            handoverTimeoutOutcome(PlaybackState.Idle, atEpisodeEnd = false).exit,
        )
    }

    @Test
    fun `旧代际的准备完成被丢弃`() {
        val first = state.begin(preparing = true)
        val second = state.begin(preparing = true)

        assertFalse(state.prepared(first))
        assertEquals(EntryPhase.Preparing, state.phase)
        assertTrue(state.prepared(second))
    }

    @Test
    fun `旧代际的准备失败被丢弃`() {
        val first = state.begin(preparing = true)
        state.begin(preparing = true)

        assertFalse(state.failed(first))
        assertEquals(EntryPhase.Preparing, state.phase)
    }

    @Test
    fun `失败后立即作废代际`() {
        val generation = state.begin(preparing = false)

        assertTrue(state.failed(generation))
        assertEquals(EntryPhase.Failed, state.phase)
        assertFalse(state.isCurrent(generation))
        assertFalse(state.failed(generation))
    }

    @Test
    fun `取消打开后阶段与代际都清空`() {
        val generation = state.begin(preparing = true)
        state.cancelEntry()

        assertNull(state.phase)
        assertFalse(state.preparingUi)
        assertFalse(state.isCurrent(generation))
        assertFalse(state.prepared(generation))
        assertFalse(state.failed(generation))
    }

    @Test
    fun `准备期的加载失败不算首次加载失败`() {
        val generation = state.begin(preparing = true)

        assertFalse(state.consumeInitialFailure(generation, firstLoad = true))
        assertEquals(EntryPhase.Preparing, state.phase)
    }

    @Test
    fun `接手前旧代际的首次加载失败被丢弃`() {
        val generation = acceptedEntry()
        val stale = generation - 1

        assertFalse(state.consumeInitialFailure(stale, firstLoad = true))
        assertEquals(EntryPhase.Accepted, state.phase)
    }

    @Test
    fun `接手的首次加载失败只消费一次`() {
        val generation = acceptedEntry()

        assertTrue(state.consumeInitialFailure(generation, firstLoad = true))
        assertEquals(EntryPhase.Failed, state.phase)
        assertFalse(state.consumeInitialFailure(generation, firstLoad = true))
    }

    @Test
    fun `非首次加载失败不关播放页`() {
        val generation = acceptedEntry()

        assertFalse(state.consumeInitialFailure(generation, firstLoad = false))
        assertEquals(EntryPhase.Accepted, state.phase)
    }

    @Test
    fun `音频首播失败留在播放页`() {
        val generation = state.begin(preparing = false)
        state.prepared(generation)
        state.accepted(generation)

        assertFalse(state.consumeInitialFailure(generation, firstLoad = true))
    }

    @Test
    fun `首次加载失败在入口阶段已结束后仍按代际收尾`() {
        val generation = acceptedEntry()
        // 控制层先上报 Ended（终态出口清掉阶段），失败事件随后到达。
        state.onPlaybackUpdate(PlaybackState.Ended, switching = false)
        assertNull(state.phase)

        assertTrue(state.consumeInitialFailure(generation, firstLoad = true))
        assertEquals(EntryPhase.Failed, state.phase)
    }

    @Test
    fun `准备期显示缓冲指示`() {
        val generation = state.begin(preparing = true)
        assertTrue(state.showsBuffering(controllerBuffering = false))

        state.prepared(generation)
        assertTrue(state.showsBuffering(controllerBuffering = false))
    }

    @Test
    fun `接手后缓冲指示延续到首个就绪`() {
        acceptedEntry()

        assertTrue(state.showsBuffering(controllerBuffering = false))
        state.onPlaybackUpdate(PlaybackState.Loading, switching = false)
        assertTrue(state.showsBuffering(controllerBuffering = false))

        state.onPlaybackUpdate(PlaybackState.Paused, switching = false)
        assertFalse(state.showsBuffering(controllerBuffering = false))
        assertTrue(state.showsBuffering(controllerBuffering = true))
    }

    @Test
    fun `音频进入时不显示入口缓冲`() {
        val generation = state.begin(preparing = false)
        state.prepared(generation)
        state.accepted(generation)

        assertFalse(state.showsBuffering(controllerBuffering = false))
        assertTrue(state.showsBuffering(controllerBuffering = true))
        assertEquals(EntryPhase.Accepted, state.phase)
    }

    @Test
    fun `新打开分配新的代际`() {
        val first = state.begin(preparing = true)
        state.cancelEntry()
        val second = state.begin(preparing = true)

        assertTrue(second > first)
        assertTrue(state.isCurrent(second))
        assertFalse(state.isCurrent(first))
    }
}
