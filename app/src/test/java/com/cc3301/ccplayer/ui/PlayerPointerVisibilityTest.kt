package com.cc3301.ccplayer.ui

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPointerVisibilityTest {

    private var now = 0L
    private val pointer = PlayerPointerVisibility(elapsedMillis = { now })

    private fun uiPresent(
        barsPresent: Boolean = false,
        lockUiPresent: Boolean = false,
        playlistPresent: Boolean = false,
        bufferingPresent: Boolean = false,
    ): Boolean = playerPointerUiPresent(barsPresent, lockUiPresent, playlistPresent, bufferingPresent)

    /** 进入播放页且播放 UI 在场：指针出现，静止计时从此刻起算。 */
    private fun showWithUi() {
        pointer.onUiPresenceChanged(uiPresent(barsPresent = true))
    }

    @Test
    fun `没有任何播放 UI 时指针不跟随鼠标`() {
        assertFalse(uiPresent())
    }

    @Test
    fun `普通控制层在场时指针跟随鼠标`() {
        assertTrue(uiPresent(barsPresent = true))
    }

    @Test
    fun `锁 UI 在场时指针跟随鼠标`() {
        assertTrue(uiPresent(lockUiPresent = true))
    }

    @Test
    fun `播放列表打开时指针跟随鼠标`() {
        assertTrue(uiPresent(playlistPresent = true))
    }

    @Test
    fun `缓冲 UI 显示时指针跟随鼠标`() {
        assertTrue(uiPresent(bufferingPresent = true))
    }

    @Test
    fun `播放 UI 在场时指针出现`() {
        showWithUi()

        assertTrue(pointer.visible)
        assertEquals(3_000L, pointer.millisUntilHide())
    }

    @Test
    fun `播放 UI 全部离场时指针立刻隐藏`() {
        showWithUi()

        now = 1_000L
        pointer.onUiPresenceChanged(uiPresent())

        assertFalse(pointer.visible)
    }

    @Test
    fun `鼠标静止满三秒后隐藏`() {
        showWithUi()

        now = 2_999L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 3_000L
        pointer.hideIfExpired()

        assertFalse(pointer.visible)
        assertEquals(-1L, pointer.millisUntilHide())
    }

    @Test
    fun `鼠标移动重置隐藏计时`() {
        showWithUi()

        now = 2_000L
        pointer.onPointerMove()

        now = 4_999L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 5_000L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)
    }

    @Test
    fun `静止隐藏后鼠标移动重新出现并重新计时`() {
        showWithUi()

        now = 3_000L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)

        now = 3_020L
        pointer.onPointerMove()
        assertTrue(pointer.visible)

        now = 6_019L
        pointer.hideIfExpired()
        assertTrue(pointer.visible)

        now = 6_020L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)
    }

    @Test
    fun `播放 UI 不在场时鼠标移动不唤出指针`() {
        pointer.onUiPresenceChanged(uiPresent())

        now = 1_000L
        pointer.onPointerMove()

        assertFalse(pointer.visible)
    }

    @Test
    fun `悬停移动重置隐藏计时`() {
        assertTrue(
            pointerHoverMoveResetsHideTimer(
                eventType = PointerEventType.Move,
                pointerType = PointerType.Mouse,
                anyButtonPressed = false,
            ),
        )
    }

    @Test
    fun `按住拖动不重置隐藏计时`() {
        assertFalse(
            pointerHoverMoveResetsHideTimer(
                eventType = PointerEventType.Move,
                pointerType = PointerType.Mouse,
                anyButtonPressed = true,
            ),
        )
    }

    @Test
    fun `滚轮不重置隐藏计时`() {
        assertFalse(
            pointerHoverMoveResetsHideTimer(
                eventType = PointerEventType.Scroll,
                pointerType = PointerType.Mouse,
                anyButtonPressed = false,
            ),
        )
    }

    @Test
    fun `右键与中键按下不重置隐藏计时`() {
        assertFalse(
            pointerHoverMoveResetsHideTimer(
                eventType = PointerEventType.Press,
                pointerType = PointerType.Mouse,
                anyButtonPressed = true,
            ),
        )
    }

    @Test
    fun `触摸拖动不重置隐藏计时`() {
        assertFalse(
            pointerHoverMoveResetsHideTimer(
                eventType = PointerEventType.Move,
                pointerType = PointerType.Touch,
                anyButtonPressed = false,
            ),
        )
    }

    @Test
    fun `指针隐藏时的左键按下只用于唤醒`() {
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
    fun `指针可见时的按下不是唤醒手势，控件点击照常执行一次`() {
        val wake = PlayerPointerWake()
        // 界面只在 press 返回 true 时唤出播放 UI。
        var wakeCount = 0
        if (wake.press(pointerVisible = true, primaryPressed = true)) wakeCount += 1
        assertEquals(0, wakeCount)
        assertFalse(wake.swallows(0L))

        // 唤醒后的双击窗口内再按下：仍不是新的唤醒手势，控件点击照常执行一次；画面层按窗口吞掉。
        wake.press(pointerVisible = false, primaryPressed = true)
        wake.onRelease(0L)
        assertFalse(wake.press(pointerVisible = true, primaryPressed = true))
        assertEquals(0, wakeCount)
        assertTrue(wake.swallows(100L))
    }

    @Test
    fun `右键与中键按下不唤醒`() {
        val wake = PlayerPointerWake()

        assertFalse(wake.press(pointerVisible = false, primaryPressed = false))
        assertFalse(wake.swallows(0L))
    }
}
