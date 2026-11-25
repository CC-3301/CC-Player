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
        assertTrue(wake.press(pointerVisible = false, primaryPressed = true))

        assertTrue(wake.swallows(0L))
        assertTrue(wake.swallows(2_000L))
    }

    @Test
    fun `唤醒手势的双击窗口从抬起起算`() {
        val wake = PlayerPointerWake()
        wake.press(pointerVisible = false, primaryPressed = true)
        wake.onRelease(1_000L)

        assertTrue(wake.swallows(1_000L))
        assertTrue(wake.swallows(1_300L))
        assertFalse(wake.swallows(1_301L))
    }

    @Test
    fun `双击窗口内的第二次按下也被吞掉且不延长窗口`() {
        val wake = PlayerPointerWake()
        wake.press(pointerVisible = false, primaryPressed = true)
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
        wake.press(pointerVisible = false, primaryPressed = true)
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

    @Test
    fun `控制层完全显示时左键按下不是唤醒手势`() {
        val wake = PlayerPointerWake()

        // 控制层已完全显示：这次按下交给控件自己执行，不唤出控制层、也不进入双击窗口。
        assertFalse(wake.press(pointerVisible = true, primaryPressed = true))
        assertFalse(wake.swallows(0L))

        wake.onRelease(0L)
        assertFalse(wake.swallows(0L))
    }

    @Test
    fun `指针隐藏时左键按下才算唤醒手势`() {
        val wake = PlayerPointerWake()

        assertTrue(wake.press(pointerVisible = false, primaryPressed = true))
        assertTrue(wake.swallows(0L))
    }

    @Test
    fun `右键与中键按下不唤醒`() {
        val wake = PlayerPointerWake()

        assertFalse(wake.press(pointerVisible = false, primaryPressed = false))
        assertFalse(wake.swallows(0L))
    }

    @Test
    fun `唤醒后的下一次按下按普通点击处理`() {
        val wake = PlayerPointerWake()
        wake.press(pointerVisible = false, primaryPressed = true)
        wake.onRelease(0L)

        // 指针已由唤醒显示：随后的按下不再算唤醒手势，控件点击照常执行一次。
        assertFalse(wake.press(pointerVisible = true, primaryPressed = true))
    }
}
