package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPointerVisibilityTest {

    private fun visible(
        barsPresent: Boolean = false,
        lockUiPresent: Boolean = false,
        playlistPresent: Boolean = false,
        bufferingPresent: Boolean = false,
    ): Boolean = playerPointerVisible(barsPresent, lockUiPresent, playlistPresent, bufferingPresent)

    @Test
    fun `没有任何播放 UI 时指针隐藏`() {
        assertFalse(visible())
    }

    @Test
    fun `普通控制层在场时指针可见`() {
        assertTrue(visible(barsPresent = true))
    }

    @Test
    fun `锁 UI 在场时指针可见`() {
        assertTrue(visible(lockUiPresent = true))
    }

    @Test
    fun `播放列表打开时指针可见`() {
        assertTrue(visible(playlistPresent = true))
    }

    @Test
    fun `缓冲 UI 显示时指针可见`() {
        assertTrue(visible(bufferingPresent = true))
    }

    @Test
    fun `任意一类播放 UI 在场都保持指针可见`() {
        assertTrue(
            visible(
                barsPresent = true,
                lockUiPresent = true,
                playlistPresent = true,
                bufferingPresent = true,
            ),
        )
    }

    @Test
    fun `唤醒手势抬手前一直只用于唤醒`() {
        val wake = PlayerPointerWake()
        wake.onWakePress()

        assertTrue(wake.swallows(0L))
        assertTrue(wake.swallows(2_000L))
    }

    @Test
    fun `唤醒手势的双击窗口从抬起起算`() {
        val wake = PlayerPointerWake()
        wake.onWakePress()
        wake.onRelease(1_000L)

        assertTrue(wake.swallows(1_000L))
        assertTrue(wake.swallows(1_300L))
        assertFalse(wake.swallows(1_301L))
    }

    @Test
    fun `双击窗口内的第二次按下也被吞掉且不延长窗口`() {
        val wake = PlayerPointerWake()
        wake.onWakePress()
        wake.onRelease(0L)

        // 第二次按下落在窗口内：整次手势吞掉，但窗口仍从第一次抬起起算。
        assertTrue(wake.swallows(100L))
        wake.onRelease(200L)

        assertTrue(wake.swallows(300L))
        assertFalse(wake.swallows(301L))
    }

    @Test
    fun `窗口结束后按下不再被吞掉`() {
        val wake = PlayerPointerWake()
        wake.onWakePress()
        wake.onRelease(0L)

        assertFalse(wake.swallows(301L))
        assertFalse(wake.swallows(10_000L))
    }

    @Test
    fun `没有唤醒手势时抬手不开始双击窗口`() {
        val wake = PlayerPointerWake()
        wake.onRelease(0L)

        assertFalse(wake.swallows(0L))
    }
}
