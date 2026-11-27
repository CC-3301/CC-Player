package com.cc3301.ccplayer.ui

import androidx.compose.ui.input.pointer.PointerEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPointerVisibilityTest {

    private var now = 0L
    private val pointer = PlayerPointerVisibility(elapsedMillis = { now })

    private fun uiPresent(
        normalControlsVisible: Boolean = false,
        lockUiVisible: Boolean = false,
        playlistVisible: Boolean = false,
        bufferingVisible: Boolean = false,
    ): Boolean = playerPointerUiVisible(normalControlsVisible, lockUiVisible, playlistVisible, bufferingVisible)

    /** 普通控制层在场判据：未锁定、抽屉关闭时的顶/底栏完全可见与在场。 */
    private fun barsOf(controls: PlayerControlsVisibility): PlayerBarsPresence = playerBarsPresence(
        locked = false,
        phase = controls.phase,
        panelBarsVisible = true,
        firstBuffering = false,
    )

    /** 进入播放页且播放 UI 完全可见：指针出现，静止计时从此刻起算。 */
    private fun showWithUi() {
        pointer.onUiVisibleChanged(uiPresent(normalControlsVisible = true))
    }

    @Test
    fun `没有任何播放 UI 时指针不跟随鼠标`() {
        assertFalse(uiPresent())
    }

    @Test
    fun `普通控制层完全可见时指针跟随鼠标`() {
        assertTrue(uiPresent(normalControlsVisible = true))
    }

    @Test
    fun `锁 UI 完全可见时指针跟随鼠标`() {
        assertTrue(uiPresent(lockUiVisible = true))
    }

    @Test
    fun `播放列表打开时指针跟随鼠标`() {
        assertTrue(uiPresent(playlistVisible = true))
    }

    @Test
    fun `缓冲 UI 显示时指针跟随鼠标`() {
        assertTrue(uiPresent(bufferingVisible = true))
    }

    @Test
    fun `播放 UI 完全可见时指针出现`() {
        showWithUi()

        assertTrue(pointer.visible)
        assertEquals(3_000L, pointer.millisUntilHide())
    }

    @Test
    fun `播放 UI 全部不可见时指针立刻隐藏`() {
        showWithUi()

        now = 1_000L
        pointer.onUiVisibleChanged(uiPresent())

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
        assertNull(pointer.millisUntilHide())
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
    fun `拖动中持续移动不隐藏指针`() {
        showWithUi()

        // 拖动进度条期间指针随每次移动重新计时，不会在拖动中途静止隐藏。
        repeat(5) {
            now += 2_000L
            pointer.onPointerMove()
            pointer.hideIfExpired()
            assertTrue(pointer.visible)
        }

        now += 3_000L
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
        pointer.onUiVisibleChanged(uiPresent())

        now = 1_000L
        pointer.onPointerMove()

        assertFalse(pointer.visible)
    }

    @Test
    fun `控制层淡出后移动鼠标不让指针重新出现`() {
        val controls = PlayerControlsVisibility(elapsedMillis = { now })
        pointer.onUiVisibleChanged(uiPresent(normalControlsVisible = barsOf(controls).shown))
        assertTrue(pointer.visible)

        // 第 4 秒开始淡出：完全可见不再成立，指针立刻隐藏。
        now = 4_000L
        controls.beginFadeOutIfExpired()
        pointer.onUiVisibleChanged(uiPresent(normalControlsVisible = barsOf(controls).shown))
        assertFalse(pointer.visible)

        // 淡出期间鼠标移动不把指针唤回来。
        now = 4_500L
        pointer.onPointerMove()
        assertFalse(pointer.visible)
    }

    @Test
    fun `鼠标移动与拖动都重置隐藏计时`() {
        assertTrue(pointerMoveResetsHideTimer(PointerEventType.Move))
    }

    @Test
    fun `滚轮不重置隐藏计时`() {
        assertFalse(pointerMoveResetsHideTimer(PointerEventType.Scroll))
    }

    @Test
    fun `右键与中键按下不重置隐藏计时`() {
        assertFalse(pointerMoveResetsHideTimer(PointerEventType.Press))
        assertFalse(pointerMoveResetsHideTimer(PointerEventType.Release))
    }

    @Test
    fun `播放 UI 完全不可见时的左键按下只用于唤醒`() {
        val wake = PlayerPointerWake()

        assertTrue(wake.press(playbackUiVisible = false, primaryPressed = true))
        assertTrue(wake.swallows(0L))
        assertTrue(wake.swallows(2_000L))
    }

    @Test
    fun `唤醒手势的双击窗口从抬起起算`() {
        val wake = PlayerPointerWake()
        wake.press(playbackUiVisible = false, primaryPressed = true)
        wake.onRelease(1_000L)

        assertTrue(wake.swallows(1_000L))
        assertTrue(wake.swallows(1_300L))
        assertFalse(wake.swallows(1_301L))
    }

    @Test
    fun `双击窗口内的第二次按下也被吞掉且不延长窗口`() {
        val wake = PlayerPointerWake()
        wake.press(playbackUiVisible = false, primaryPressed = true)
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
        wake.press(playbackUiVisible = false, primaryPressed = true)
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
    fun `播放 UI 完全可见时第一次左键按下交给控件且只执行一次`() {
        val wake = PlayerPointerWake()
        var executions = 0

        // 控件点击：播放 UI 完全可见，这次按下不进唤醒手势，手势层也不吞它，控件只执行一次。
        if (!wake.press(playbackUiVisible = true, primaryPressed = true)) executions += 1
        wake.onRelease(0L)

        assertEquals(1, executions)
        assertFalse(wake.swallows(0L))
        assertFalse(wake.swallows(300L))
    }

    @Test
    fun `播放 UI 完全可见但指针已静止隐藏时左键按下交给控件且指针重新出现`() {
        showWithUi()
        now = 3_000L
        pointer.hideIfExpired()
        assertFalse(pointer.visible)

        val wake = PlayerPointerWake()
        // 控制层满 5 秒才收：此刻播放 UI 完全可见，这一下不是唤醒，控件照常执行。
        assertFalse(wake.press(playbackUiVisible = true, primaryPressed = true))
        assertFalse(wake.swallows(now))

        // 界面在按下时让指针立刻出现。
        pointer.show()
        assertTrue(pointer.visible)
    }

    @Test
    fun `锁定态播放 UI 不可见时第一次左键只显示锁 UI 不解锁`() {
        val lock = PlayerLock(elapsedMillis = { now })
        lock.tapLockButton()

        // 锁 UI 满 5 秒自动收起，此时播放 UI 完全不可见。
        now = 4_000L
        lock.beginUnlockFadeOutIfExpired()
        now = 5_000L
        lock.completeUnlockFadeOut()
        assertFalse(lock.unlockVisible)

        val wake = PlayerPointerWake()
        val waking = wake.press(playbackUiVisible = false, primaryPressed = true)
        assertTrue(waking)
        assertTrue(wake.swallows(now))

        // 界面在唤醒时只让锁 UI 重新出现，不解锁。
        lock.tapScreen()
        assertTrue(lock.locked)
        assertTrue(lock.unlockVisible)
        assertFalse(lock.unlockFading)
    }

    @Test
    fun `右键与中键按下不唤醒`() {
        val wake = PlayerPointerWake()

        assertFalse(wake.press(playbackUiVisible = false, primaryPressed = false))
        assertFalse(wake.swallows(0L))
    }
}
