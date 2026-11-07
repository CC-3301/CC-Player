package com.cc3301.ccplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 片尾的统一判据：已结束与暂停在片尾位置都算片尾。 */
class PlaybackEpisodeEndTest {

    @Test
    fun `已结束算片尾`() {
        assertTrue(isAtEpisodeEnd(PlaybackState.Ended, positionMillis = 0, durationMillis = 0))
    }

    @Test
    fun `暂停在片尾位置算片尾`() {
        assertTrue(isAtEpisodeEnd(PlaybackState.Paused, positionMillis = 600_000, durationMillis = 600_000))
        assertTrue(isAtEpisodeEnd(PlaybackState.Paused, positionMillis = 599_500, durationMillis = 600_000))
    }

    @Test
    fun `暂停在片尾之外不算片尾`() {
        assertFalse(isAtEpisodeEnd(PlaybackState.Paused, positionMillis = 598_000, durationMillis = 600_000))
    }

    @Test
    fun `播放中不算片尾`() {
        assertFalse(isAtEpisodeEnd(PlaybackState.Playing, positionMillis = 600_000, durationMillis = 600_000))
    }

    @Test
    fun `时长未知时暂停不算片尾`() {
        assertFalse(isAtEpisodeEnd(PlaybackState.Paused, positionMillis = 600_000, durationMillis = 0))
    }

    @Test
    fun `空闲与加载中不算片尾`() {
        assertFalse(isAtEpisodeEnd(PlaybackState.Idle, positionMillis = 0, durationMillis = 0))
        assertFalse(isAtEpisodeEnd(PlaybackState.Loading, positionMillis = 0, durationMillis = 0))
    }
}
