package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPlayPauseCommandTest {

    @Test
    fun `引擎在播时播放键投递暂停`() {
        assertEquals(PlayerPlayPauseCommand.Pause, playPauseCommand(PlaybackState.Playing))
    }

    @Test
    fun `引擎已暂停时播放键投递继续`() {
        assertEquals(PlayerPlayPauseCommand.Resume, playPauseCommand(PlaybackState.Paused))
    }

    @Test
    fun `引擎状态未就绪时不投递命令`() {
        listOf(PlaybackState.Idle, PlaybackState.Loading, PlaybackState.Ended).forEach { state ->
            assertNull(playPauseCommand(state))
        }
    }
}
