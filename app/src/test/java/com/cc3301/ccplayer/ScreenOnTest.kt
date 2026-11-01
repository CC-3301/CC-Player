package com.cc3301.ccplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/** 屏幕常亮只在前台且在播放时打开，其余状态立即释放。 */
class ScreenOnTest {

    private val applied = mutableListOf<Boolean>()
    private val flag = ScreenOnFlag { applied += it }

    @Test
    fun `前台播放时打开常亮，暂停立即释放`() {
        flag.update(foreground = true, playing = true)
        flag.update(foreground = true, playing = false)

        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `退后台立即释放常亮`() {
        flag.update(foreground = true, playing = true)
        flag.update(foreground = false, playing = true)

        assertEquals(listOf(true, false), applied)
    }

    @Test
    fun `同一状态重复上报不重复改写窗口标志`() {
        flag.update(foreground = true, playing = true)
        flag.update(foreground = true, playing = true)
        flag.update(foreground = false, playing = false)
        flag.update(foreground = false, playing = false)

        assertEquals(listOf(true, false), applied)
    }
}
