package com.cc3301.ccplayer

import com.cc3301.ccplayer.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 屏幕常亮只在前台且播放页在场、播放或加载时打开，其余状态立即释放。 */
class ScreenOnTest {

    private val applied = mutableListOf<Boolean>()
    private val flag = ScreenOnFlag { applied += it }

    @Test
    fun `前台播放页播放与加载时常亮，其余状态不常亮`() {
        assertTrue(keepScreenOn(foreground = true, playerVisible = true, state = PlaybackState.Playing))
        assertTrue(keepScreenOn(foreground = true, playerVisible = true, state = PlaybackState.Loading))
        assertFalse(keepScreenOn(foreground = true, playerVisible = true, state = PlaybackState.Idle))
        assertFalse(keepScreenOn(foreground = true, playerVisible = true, state = PlaybackState.Paused))
        assertFalse(keepScreenOn(foreground = true, playerVisible = true, state = PlaybackState.Ended))
    }

    @Test
    fun `离开播放页即使仍在播放也不常亮`() {
        assertFalse(keepScreenOn(foreground = true, playerVisible = false, state = PlaybackState.Playing))
        assertFalse(keepScreenOn(foreground = true, playerVisible = false, state = PlaybackState.Loading))
    }

    @Test
    fun `退后台时不常亮`() {
        assertFalse(keepScreenOn(foreground = false, playerVisible = true, state = PlaybackState.Playing))
        assertFalse(keepScreenOn(foreground = false, playerVisible = true, state = PlaybackState.Loading))
        assertFalse(keepScreenOn(foreground = false, playerVisible = true, state = PlaybackState.Idle))
        assertFalse(keepScreenOn(foreground = false, playerVisible = true, state = PlaybackState.Paused))
        assertFalse(keepScreenOn(foreground = false, playerVisible = true, state = PlaybackState.Ended))
    }

    @Test
    fun `加载中打开常亮，暂停立即释放`() {
        flag.update(foreground = true, playerVisible = true, state = PlaybackState.Loading)
        flag.update(foreground = true, playerVisible = true, state = PlaybackState.Paused)

        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `离开播放页立即释放常亮`() {
        flag.update(foreground = true, playerVisible = true, state = PlaybackState.Playing)
        flag.update(foreground = true, playerVisible = false, state = PlaybackState.Playing)

        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `退后台立即释放常亮`() {
        flag.update(foreground = true, playerVisible = true, state = PlaybackState.Playing)
        flag.update(foreground = false, playerVisible = true, state = PlaybackState.Playing)

        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `同一状态重复上报不重复改写窗口标志`() {
        flag.update(foreground = true, playerVisible = true, state = PlaybackState.Playing)
        flag.update(foreground = true, playerVisible = true, state = PlaybackState.Playing)
        flag.update(foreground = false, playerVisible = true, state = PlaybackState.Playing)
        flag.update(foreground = false, playerVisible = true, state = PlaybackState.Playing)

        assertEquals(listOf(true, false), applied)
    }
}
