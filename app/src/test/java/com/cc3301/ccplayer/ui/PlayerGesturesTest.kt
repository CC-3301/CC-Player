package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.isInitialLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGesturesTest {

    private val width = 900
    private val threshold = 9f

    @Test
    fun `左右两侧音量区各占两成，中间为跳转区`() {
        assertEquals(GestureZone.LeftVolume, gestureZone(0f, width))
        assertEquals(GestureZone.LeftVolume, gestureZone(161.9f, width))
        assertEquals(GestureZone.Center, gestureZone(162f, width))
        assertEquals(GestureZone.Center, gestureZone(450f, width))
        assertEquals(GestureZone.Center, gestureZone(737.9f, width))
        assertEquals(GestureZone.RightVolume, gestureZone(738f, width))
        assertEquals(GestureZone.RightVolume, gestureZone(899.9f, width))
    }

    @Test
    fun `左侧双击后退 10 秒`() {
        assertEquals(DoubleTapAction.Seek(-10_000L), doubleTapAction(100f, width))
    }

    @Test
    fun `中间双击切换播放暂停`() {
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(450f, width))
    }

    @Test
    fun `右侧双击前进 10 秒`() {
        assertEquals(DoubleTapAction.Seek(10_000L), doubleTapAction(800f, width))
    }

    @Test
    fun `双击分区边界归中间与右侧`() {
        assertEquals(DoubleTapAction.Seek(-10_000L), doubleTapAction(161.9f, width))
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(162f, width))
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(737.9f, width))
        assertEquals(DoubleTapAction.Seek(10_000L), doubleTapAction(738f, width))
    }

    @Test
    fun `整屏宽对应五分钟`() {
        assertEquals(300_000L, seekDeltaMillis(900f, width))
        assertEquals(-300_000L, seekDeltaMillis(-900f, width))
    }

    @Test
    fun `半屏对应两分半`() {
        assertEquals(150_000L, seekDeltaMillis(450f, width))
        assertEquals(-150_000L, seekDeltaMillis(-450f, width))
    }

    @Test
    fun `零位移或零宽度不跳转`() {
        assertEquals(0L, seekDeltaMillis(0f, width))
        assertEquals(0L, seekDeltaMillis(450f, 0))
    }

    @Test
    fun `音量位移未超过 9dp 不进入调整`() {
        assertNull(dragGesture(GestureZone.LeftVolume, 0f, 9f, threshold))
        assertNull(dragGesture(GestureZone.RightVolume, 0f, -9f, threshold))
    }

    @Test
    fun `音量位移超过 9dp 进入调整`() {
        assertEquals(DragGesture.Volume, dragGesture(GestureZone.LeftVolume, 0f, 9.1f, threshold))
        assertEquals(DragGesture.Volume, dragGesture(GestureZone.RightVolume, 0f, -9.1f, threshold))
    }

    @Test
    fun `中间区域左右拖动进入跳转`() {
        assertEquals(DragGesture.Seek, dragGesture(GestureZone.Center, 9.1f, 0f, threshold))
        assertEquals(DragGesture.Seek, dragGesture(GestureZone.Center, -9.1f, 0f, threshold))
    }

    @Test
    fun `中间上下拖动与两侧左右拖动不响应`() {
        assertNull(dragGesture(GestureZone.Center, 0f, 20f, threshold))
        assertNull(dragGesture(GestureZone.LeftVolume, 20f, 0f, threshold))
        assertNull(dragGesture(GestureZone.RightVolume, -20f, 0f, threshold))
    }

    @Test
    fun `双击窗口为 300 毫秒`() {
        assertEquals(300L, DOUBLE_TAP_WINDOW_MILLIS)
    }

    @Test
    fun `位移阈值与音量区宽度`() {
        assertEquals(9f, VOLUME_DRAG_THRESHOLD_DP)
        assertEquals(0.18f, VOLUME_ZONE_FRACTION)
    }

    @Test
    fun `未锁定且无面板时手势放行`() {
        assertTrue(gesturesAllowed(locked = false, panelOpen = false))
        assertFalse(gesturesAllowed(locked = true, panelOpen = false))
        assertFalse(gesturesAllowed(locked = false, panelOpen = true))
        assertFalse(gesturesAllowed(locked = true, panelOpen = true))
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
        assertEquals(false, inputAllowed(PlayerInput.VolumeDrag, locked = true))
        assertEquals(false, inputAllowed(PlayerInput.SeekDrag, locked = true))
        assertEquals(false, inputAllowed(PlayerInput.ControlBar, locked = true))
        assertEquals(true, inputAllowed(PlayerInput.UnlockButton, locked = true))
    }

    @Test
    fun `切集期间只放行返回、上一集与下一集`() {
        val allowed = PlayerAction.entries.filter { actionAllowed(it, switching = true, initialLoading = false, restoring = false) }.toSet()
        assertEquals(setOf(PlayerAction.Back, PlayerAction.Previous, PlayerAction.Next), allowed)
        assertEquals(7, PlayerAction.entries.count { !actionAllowed(it, switching = true, initialLoading = false, restoring = false) })
    }

    @Test
    fun `未切集时所有操作放行`() {
        PlayerAction.entries.forEach { action ->
            assertEquals(action.name, true, actionAllowed(action, switching = false, initialLoading = false, restoring = false))
        }
    }

    @Test
    fun `首次加载期间只放行返回`() {
        val allowed = PlayerAction.entries
            .filter { actionAllowed(it, switching = false, initialLoading = true, restoring = false) }
            .toSet()
        assertEquals(setOf(PlayerAction.Back), allowed)
    }

    @Test
    fun `恢复原片期间额外放行播放暂停`() {
        val allowed = PlayerAction.entries
            .filter { actionAllowed(it, switching = true, initialLoading = false, restoring = true) }
            .toSet()
        assertEquals(
            setOf(PlayerAction.Back, PlayerAction.Previous, PlayerAction.Next, PlayerAction.PlayPause),
            allowed,
        )
    }

    @Test
    fun `首次加载完成后所有操作恢复`() {
        PlayerAction.entries.forEach { action ->
            assertEquals(
                action.name,
                true,
                actionAllowed(action, switching = false, initialLoading = false, restoring = false),
            )
        }
    }

    @Test
    fun `加载中且未切集才算首次加载`() {
        assertTrue(isInitialLoading(PlaybackState.Loading, switching = false))
        assertFalse(isInitialLoading(PlaybackState.Loading, switching = true))
        assertFalse(isInitialLoading(PlaybackState.Playing, switching = false))
    }
}
