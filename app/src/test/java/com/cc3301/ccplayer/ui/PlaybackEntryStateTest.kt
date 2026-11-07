package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEntryStateTest {

    private val state = PlaybackEntryState()

    @Test
    fun `准备期保持缓冲并限制输入`() {
        state.enter(preparing = true)

        assertTrue(state.openingPlayback)
        assertTrue(state.awaitingInitialPlayback)
        assertTrue(state.initialLoading(PlaybackState.Idle, switching = false))
    }

    @Test
    fun `首次加载期间保持限制，转入切集后解除`() {
        state.enter(preparing = true)
        state.onPlaybackUpdate(PlaybackState.Loading, buffering = false, switching = false)
        assertTrue(state.initialLoading(PlaybackState.Loading, switching = false))

        state.onPlaybackUpdate(PlaybackState.Loading, buffering = false, switching = true)
        assertFalse(state.initialLoading(PlaybackState.Loading, switching = true))
    }

    @Test
    fun `播放控制层接管缓冲后解除准备态`() {
        state.enter(preparing = true)
        state.onPlaybackUpdate(PlaybackState.Loading, buffering = true, switching = false)

        assertFalse(state.openingPlayback)
        assertTrue(state.awaitingInitialPlayback)
        assertTrue(state.initialLoading(PlaybackState.Loading, switching = false))
    }

    @Test
    fun `首次加载就绪后解除准备与等待`() {
        state.enter(preparing = true)
        state.onPlaybackUpdate(PlaybackState.Playing, buffering = false, switching = false)

        assertFalse(state.openingPlayback)
        assertFalse(state.awaitingInitialPlayback)
        assertFalse(state.initialLoading(PlaybackState.Playing, switching = false))
    }

    @Test
    fun `转入切集后不再按首次加载限制`() {
        state.enter(preparing = true)
        state.onPlaybackUpdate(PlaybackState.Loading, buffering = false, switching = true)

        assertFalse(state.openingPlayback)
        assertFalse(state.awaitingInitialPlayback)
        assertFalse(state.initialLoading(PlaybackState.Loading, switching = true))
    }

    @Test
    fun `首次失败只能退回一次`() {
        state.enter(preparing = true)

        assertTrue(state.consumeInitialFailure())
        assertFalse(state.consumeInitialFailure())
    }

    @Test
    fun `音频准备完成后进入不显示准备缓冲`() {
        state.enter(preparing = false)

        assertFalse(state.openingPlayback)
        assertFalse(state.awaitingInitialPlayback)
    }

    @Test
    fun `音频首播失败留在播放页`() {
        state.enter(preparing = false)
        state.onPlaybackUpdate(PlaybackState.Loading, buffering = false, switching = false)

        assertTrue(state.initialLoading(PlaybackState.Loading, switching = false))
        assertFalse(state.consumeInitialFailure())
    }

    @Test
    fun `准备期或控制层缓冲时显示缓冲指示`() {
        state.enter(preparing = true)

        assertTrue(state.showingBuffering(controllerBuffering = false))
        assertTrue(state.showingBuffering(controllerBuffering = true))

        state.onPlaybackUpdate(PlaybackState.Loading, buffering = true, switching = false)
        assertTrue(state.showingBuffering(controllerBuffering = true))

        state.onPlaybackUpdate(PlaybackState.Playing, buffering = false, switching = false)
        assertFalse(state.showingBuffering(controllerBuffering = false))
    }

    @Test
    fun `离开播放页清除准备态`() {
        state.enter(preparing = true)
        state.reset()

        assertFalse(state.openingPlayback)
        assertFalse(state.awaitingInitialPlayback)
        assertEquals(false, state.initialLoading(PlaybackState.Idle, switching = false))
    }
}
