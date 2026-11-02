package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPlayPauseCommandTest {

    @Test
    fun `引擎在播时播放键投递暂停`() {
        assertEquals(
            PlayerPlayPauseCommand.Pause,
            playPauseCommand(PlaybackState.Playing, switching = false, pausedIntent = false),
        )
    }

    @Test
    fun `引擎已暂停时播放键投递继续`() {
        assertEquals(
            PlayerPlayPauseCommand.Resume,
            playPauseCommand(PlaybackState.Paused, switching = false, pausedIntent = true),
        )
    }

    @Test
    fun `切集窗口按引擎播放意图投递而不是翻转`() {
        assertEquals(
            PlayerPlayPauseCommand.Pause,
            playPauseCommand(PlaybackState.Loading, switching = true, pausedIntent = false),
        )
        assertEquals(
            PlayerPlayPauseCommand.Resume,
            playPauseCommand(PlaybackState.Loading, switching = true, pausedIntent = true),
        )
    }

    @Test
    fun `首次加载与播完不投递命令`() {
        assertNull(playPauseCommand(PlaybackState.Loading, switching = false, pausedIntent = false))
        listOf(PlaybackState.Idle, PlaybackState.Ended).forEach { state ->
            assertNull(playPauseCommand(state, switching = false, pausedIntent = false))
        }
    }

    @Test
    fun `同一命令重复投递不改变结果`() {
        var paused = false
        val command = playPauseCommand(PlaybackState.Playing, switching = false, pausedIntent = false)!!

        applyPlayPauseCommand(command, pause = { paused = true }, resume = { paused = false })
        applyPlayPauseCommand(command, pause = { paused = true }, resume = { paused = false })

        assertEquals(PlayerPlayPauseCommand.Pause, command)
        assertTrue(paused)
    }

    @Test
    fun `继续命令重复投递仍是继续`() {
        var paused = true
        val command = playPauseCommand(PlaybackState.Paused, switching = false, pausedIntent = true)!!

        applyPlayPauseCommand(command, pause = { paused = true }, resume = { paused = false })
        applyPlayPauseCommand(command, pause = { paused = true }, resume = { paused = false })

        assertEquals(PlayerPlayPauseCommand.Resume, command)
        assertFalse(paused)
    }
}
