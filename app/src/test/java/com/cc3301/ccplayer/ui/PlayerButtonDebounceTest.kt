package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerButtonDebounceTest {

    private var now = 0L
    private val debounce = PlayerButtonDebounce(elapsedMillis = { now })

    @Test
    fun `去抖窗口为 300 毫秒`() {
        assertEquals(300L, PLAYER_BUTTON_DEBOUNCE_MILLIS)
    }

    @Test
    fun `同一按钮 300 毫秒内只放行第一击`() {
        assertTrue(debounce.accept(PlayerPressButton.PlayPause))

        now = 299L
        assertFalse(debounce.accept(PlayerPressButton.PlayPause))
    }

    @Test
    fun `同一按钮满 300 毫秒再次放行`() {
        assertTrue(debounce.accept(PlayerPressButton.PlayPause))

        now = 300L
        assertTrue(debounce.accept(PlayerPressButton.PlayPause))
    }

    @Test
    fun `只有播放暂停去抖，上一集与下一集不参与`() {
        // 上一集/下一集每一击都要前移切集目标，加入去抖就会吞掉目标前移。
        assertEquals(listOf(PlayerPressButton.PlayPause), PlayerPressButton.entries)
    }

    @Test
    fun `被压掉的重复点击不延长窗口`() {
        assertTrue(debounce.accept(PlayerPressButton.PlayPause))

        now = 200L
        assertFalse(debounce.accept(PlayerPressButton.PlayPause))

        // 窗口仍从上一击生效的时刻起算。
        now = 300L
        assertTrue(debounce.accept(PlayerPressButton.PlayPause))
    }
}
