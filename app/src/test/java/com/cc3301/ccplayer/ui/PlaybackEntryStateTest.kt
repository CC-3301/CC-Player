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
        assertTrue(state.isPreparing)
        assertTrue(state.initialLoading(PlaybackState.Idle, switching = false))
    }

    @Test
    fun `准备完成后进入等待接手`() {
        val generation = state.begin(preparing = true)

        assertTrue(state.prepared(generation))

        assertEquals(EntryPhase.AwaitingHandover, state.phase)
        assertTrue(state.isPreparing)
    }

    @Test
    fun `接手后恢复界面`() {
        val generation = acceptedEntry()

        assertEquals(EntryPhase.Accepted, state.phase)
        assertFalse(state.isPreparing)
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
    fun `准备期不受上一会话的就绪上报影响`() {
        state.begin(preparing = true)

        state.onPlaybackUpdate(PlaybackState.Playing, switching = false)

        assertEquals(EntryPhase.Preparing, state.phase)
        assertTrue(state.isPreparing)
    }

    @Test
    fun `等待接手不受上一会话的就绪上报影响`() {
        val generation = state.begin(preparing = true)
        state.prepared(generation)

        state.onPlaybackUpdate(PlaybackState.Paused, switching = false)

        assertEquals(EntryPhase.AwaitingHandover, state.phase)
    }

    @Test
    fun `接手后转入切集结束入口阶段`() {
        acceptedEntry()

        state.onPlaybackUpdate(PlaybackState.Loading, switching = true)

        assertNull(state.phase)
    }

    @Test
    fun `准备期不计等待接手兜底`() {
        state.begin(preparing = true)

        assertFalse(state.onHandoverTick(deltaMillis = 10_000, foreground = true))
    }

    @Test
    fun `已接手后不再按等待接手兜底`() {
        acceptedEntry()

        assertFalse(state.onHandoverTick(deltaMillis = 10_000, foreground = true))
        assertFalse(state.abortAwaitingHandover())
    }

    @Test
    fun `等待接手只在前台累计`() {
        val generation = state.begin(preparing = true)
        state.prepared(generation)

        assertFalse(state.onHandoverTick(deltaMillis = 4_000, foreground = false))
        assertFalse(state.onHandoverTick(deltaMillis = 4_000, foreground = true))
        assertTrue(state.onHandoverTick(deltaMillis = 1_000, foreground = true))
    }

    @Test
    fun `兜底到点中止仍在等待接手的打开`() {
        val generation = state.begin(preparing = true)
        state.prepared(generation)
        state.onHandoverTick(deltaMillis = 5_000, foreground = true)

        assertTrue(state.abortAwaitingHandover())
        assertEquals(EntryPhase.Failed, state.phase)
        assertFalse(state.abortAwaitingHandover())
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
    fun `取消后旧代际的结果被丢弃`() {
        val generation = state.begin(preparing = true)
        state.cancelEntry()

        assertEquals(EntryPhase.Cancelled, state.phase)
        assertFalse(state.prepared(generation))
        assertFalse(state.failed(generation))
        assertFalse(state.isCurrent(generation))
    }

    @Test
    fun `准备期的加载失败不算首次加载失败`() {
        state.begin(preparing = true)

        assertFalse(state.consumeInitialFailure())
        assertEquals(EntryPhase.Preparing, state.phase)
    }

    @Test
    fun `等待接手的加载失败不算首次加载失败`() {
        val generation = state.begin(preparing = true)
        state.prepared(generation)

        assertFalse(state.consumeInitialFailure())
        assertEquals(EntryPhase.AwaitingHandover, state.phase)
    }

    @Test
    fun `接手后的首次加载失败只消费一次`() {
        acceptedEntry()

        assertTrue(state.consumeInitialFailure())
        assertEquals(EntryPhase.Failed, state.phase)
        assertFalse(state.consumeInitialFailure())
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
    fun `音频准备完成后进入不显示准备缓冲`() {
        val generation = state.begin(preparing = false)

        assertNull(state.phase)
        assertTrue(state.prepared(generation))
        assertNull(state.phase)
        assertFalse(state.showsBuffering(controllerBuffering = false))
        assertFalse(state.isPreparing)
    }

    @Test
    fun `音频首播失败留在播放页`() {
        val generation = state.begin(preparing = false)
        state.prepared(generation)
        state.accepted(generation)

        assertFalse(state.consumeInitialFailure())
        assertNull(state.phase)
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

    @Test
    fun `清空后不再属于当前打开`() {
        val generation = state.begin(preparing = true)
        state.reset()

        assertNull(state.phase)
        assertFalse(state.isCurrent(generation))
    }
}
