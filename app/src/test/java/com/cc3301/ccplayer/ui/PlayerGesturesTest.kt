package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGesturesTest {

    private val width = 900

    @Test
    fun `左侧三分之一双击倒退 10 秒`() {
        assertEquals(DoubleTapAction.Seek(-10_000L), doubleTapAction(150f, width))
    }

    @Test
    fun `中间三分之一双击切换播放暂停`() {
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(450f, width))
    }

    @Test
    fun `右侧三分之一双击前进 10 秒`() {
        assertEquals(DoubleTapAction.Seek(10_000L), doubleTapAction(750f, width))
    }

    @Test
    fun `恰好落在三分之一与三分之二分界线上`() {
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(300f, width))
        assertEquals(DoubleTapAction.Seek(10_000L), doubleTapAction(600f, width))
    }

    @Test
    fun `左半屏上下拖调亮度`() {
        assertEquals(DragTarget.Brightness, dragTarget(150f, width))
    }

    @Test
    fun `右半屏上下拖调音量`() {
        assertEquals(DragTarget.Volume, dragTarget(750f, width))
    }

    @Test
    fun `恰好落在半屏分界线上归音量`() {
        assertEquals(DragTarget.Volume, dragTarget(450f, width))
    }

    @Test
    fun `未锁定时所有输入放行`() {
        PlayerInput.entries.forEach { input ->
            assertEquals(input.name, true, inputAllowed(input, locked = false))
        }
    }

    @Test
    fun `锁定后除解锁按钮外全部输入被拒`() {
        assertEquals(false, inputAllowed(PlayerInput.Tap, locked = true))
        assertEquals(false, inputAllowed(PlayerInput.DoubleTap, locked = true))
        assertEquals(false, inputAllowed(PlayerInput.LevelDrag, locked = true))
        assertEquals(false, inputAllowed(PlayerInput.ControlBar, locked = true))
        assertEquals(true, inputAllowed(PlayerInput.UnlockButton, locked = true))
    }
}
