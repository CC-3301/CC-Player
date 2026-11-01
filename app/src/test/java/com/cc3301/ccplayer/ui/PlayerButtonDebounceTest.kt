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
    fun `去抖入口只有播放暂停`() {
        // 上一集/下一集每一击都要前移切集目标，去抖会吞掉目标前移。
        // 类型只有一个成员：这两类按钮没有能传给 accept 的入口，回归会先在编译期消失。
        assertEquals(setOf(PlayerPressButton.PlayPause), PlayerPressButton.entries.toSet())
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
