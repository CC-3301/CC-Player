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
        assertEquals(DoubleTapAction.Seek(-10_000L, DoubleTapSide.Left), doubleTapAction(100f, width))
    }

    @Test
    fun `中间双击切换播放暂停`() {
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(450f, width))
    }

    @Test
    fun `右侧双击前进 10 秒`() {
        assertEquals(DoubleTapAction.Seek(10_000L, DoubleTapSide.Right), doubleTapAction(800f, width))
    }

    @Test
    fun `双击分区边界归中间与右侧`() {
        assertEquals(DoubleTapAction.Seek(-10_000L, DoubleTapSide.Left), doubleTapAction(161.9f, width))
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(162f, width))
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(737.9f, width))
        assertEquals(DoubleTapAction.Seek(10_000L, DoubleTapSide.Right), doubleTapAction(738f, width))
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
    fun `滚轮上滚后退五秒、下滚前进五秒`() {
        assertEquals(-5_000L, wheelSeekStep(-1f))
        assertEquals(5_000L, wheelSeekStep(1f))
        assertNull(wheelSeekStep(0f))
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
    fun `切集期间放行返回、上一集、下一集与播放暂停`() {
        val allowed = PlayerAction.entries.filter { actionAllowed(it, switching = true, initialLoading = false, restoring = false) }.toSet()
        assertEquals(
            setOf(PlayerAction.Back, PlayerAction.Previous, PlayerAction.Next, PlayerAction.PlayPause),
            allowed,
        )
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
    fun `切集期间上下一集可点且外观正常`() {
        listOf(PlayerAction.Previous, PlayerAction.Next).forEach { action ->
            assertEquals(action.name, true, actionAllowed(action, switching = true, initialLoading = false, restoring = false))
            assertEquals(
                action.name,
                true,
                actionLooksEnabled(
                    action,
                    canGoPrevious = true,
                    canGoNext = true,
                    switching = true,
                    initialLoading = false,
                    restoring = false,
                ),
            )
        }
    }

    @Test
    fun `切集期间被拦截的动作按不可用外观显示`() {
        listOf(
            PlayerAction.Seek,
            PlayerAction.Lock,
            PlayerAction.More,
            PlayerAction.Playlist,
            PlayerAction.Subtitles,
            PlayerAction.Rotate,
        ).forEach { action ->
            assertEquals(
                action.name,
                false,
                actionAllowed(action, switching = true, initialLoading = false, restoring = false),
            )
            assertEquals(
                action.name,
                false,
                actionLooksEnabled(
                    action,
                    canGoPrevious = true,
                    canGoNext = true,
                    switching = true,
                    initialLoading = false,
                    restoring = false,
                ),
            )
        }
    }

    @Test
    fun `切集期间播放暂停可点且外观正常`() {
        assertEquals(
            true,
            actionAllowed(PlayerAction.PlayPause, switching = true, initialLoading = false, restoring = false),
        )
        assertEquals(
            true,
            actionLooksEnabled(
                PlayerAction.PlayPause,
                canGoPrevious = false,
                canGoNext = false,
                switching = true,
                initialLoading = false,
                restoring = false,
            ),
        )
    }

    @Test
    fun `首次加载时上下一集显示为不可用`() {
        listOf(PlayerAction.Previous, PlayerAction.Next).forEach { action ->
            assertEquals(
                action.name,
                false,
                actionLooksEnabled(
                    action,
                    canGoPrevious = true,
                    canGoNext = true,
                    switching = false,
                    initialLoading = true,
                    restoring = false,
                ),
            )
        }
    }

    @Test
    fun `首次加载时其余控件也按不可用外观显示`() {
        listOf(
            PlayerAction.PlayPause,
            PlayerAction.Seek,
            PlayerAction.Lock,
            PlayerAction.More,
            PlayerAction.Playlist,
            PlayerAction.Subtitles,
            PlayerAction.Rotate,
        ).forEach { action ->
            assertEquals(
                action.name,
                false,
                actionLooksEnabled(
                    action,
                    canGoPrevious = true,
                    canGoNext = true,
                    switching = false,
                    initialLoading = true,
                    restoring = false,
                ),
            )
        }
        assertEquals(
            true,
            actionLooksEnabled(
                PlayerAction.Back,
                canGoPrevious = true,
                canGoNext = true,
                switching = false,
                initialLoading = true,
                restoring = false,
            ),
        )
    }

    @Test
    fun `恢复期间其余控件外观保持正常`() {
        listOf(
            PlayerAction.PlayPause,
            PlayerAction.Seek,
            PlayerAction.Lock,
            PlayerAction.More,
            PlayerAction.Playlist,
            PlayerAction.Subtitles,
            PlayerAction.Rotate,
        ).forEach { action ->
            assertEquals(
                action.name,
                true,
                actionLooksEnabled(
                    action,
                    canGoPrevious = true,
                    canGoNext = true,
                    switching = true,
                    initialLoading = false,
                    restoring = true,
                ),
            )
        }
    }

    @Test
    fun `恢复期停在首集时上一集不可用`() {
        assertEquals(
            false,
            actionLooksEnabled(
                PlayerAction.Previous,
                canGoPrevious = false,
                canGoNext = true,
                switching = false,
                initialLoading = false,
                restoring = true,
            ),
        )
        assertEquals(
            true,
            actionLooksEnabled(
                PlayerAction.Next,
                canGoPrevious = false,
                canGoNext = true,
                switching = false,
                initialLoading = false,
                restoring = true,
            ),
        )
    }

    @Test
    fun `恢复期停在末集时下一集不可用`() {
        assertEquals(
            false,
            actionLooksEnabled(
                PlayerAction.Next,
                canGoPrevious = true,
                canGoNext = false,
                switching = false,
                initialLoading = false,
                restoring = true,
            ),
        )
        assertEquals(
            true,
            actionLooksEnabled(
                PlayerAction.Previous,
                canGoPrevious = true,
                canGoNext = false,
                switching = false,
                initialLoading = false,
                restoring = true,
            ),
        )
    }

    @Test
    fun `上下一集不可用时外观禁用`() {
        assertEquals(
            false,
            actionLooksEnabled(
                PlayerAction.Previous,
                canGoPrevious = false,
                canGoNext = true,
                switching = false,
                initialLoading = false,
                restoring = false,
            ),
        )
        assertEquals(
            false,
            actionLooksEnabled(
                PlayerAction.Next,
                canGoPrevious = true,
                canGoNext = false,
                switching = false,
                initialLoading = false,
                restoring = false,
            ),
        )
        assertEquals(
            true,
            actionLooksEnabled(
                PlayerAction.Previous,
                canGoPrevious = true,
                canGoNext = false,
                switching = false,
                initialLoading = false,
                restoring = false,
            ),
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

    @Test
    fun `双击跳转按当前位置与时长夹取实际位移`() {
        assertEquals(
            RelativeSeekTarget(targetMillis = 20_000L, deltaMillis = 10_000L),
            relativeSeekTarget(positionMillis = 10_000L, durationMillis = 100_000L, deltaMillis = 10_000L),
        )
        assertEquals(
            RelativeSeekTarget(targetMillis = 100_000L, deltaMillis = 5_000L),
            relativeSeekTarget(positionMillis = 95_000L, durationMillis = 100_000L, deltaMillis = 10_000L),
        )
        assertEquals(
            RelativeSeekTarget(targetMillis = 0L, deltaMillis = -5_000L),
            relativeSeekTarget(positionMillis = 5_000L, durationMillis = 100_000L, deltaMillis = -10_000L),
        )
    }

    @Test
    fun `时长未知或已到边界时不跳转`() {
        assertNull(relativeSeekTarget(positionMillis = 10_000L, durationMillis = 0L, deltaMillis = 10_000L))
        assertEquals(
            RelativeSeekTarget(targetMillis = 100_000L, deltaMillis = 0L),
            relativeSeekTarget(positionMillis = 100_000L, durationMillis = 100_000L, deltaMillis = 10_000L),
        )
        assertEquals(
            RelativeSeekTarget(targetMillis = 0L, deltaMillis = 0L),
            relativeSeekTarget(positionMillis = 0L, durationMillis = 100_000L, deltaMillis = -10_000L),
        )
    }

    @Test
    fun `锁定或面板打开时不触发双击反馈`() {
        assertFalse(doubleTapFeedbackAllowed(false, true, false, false, false))
        assertFalse(doubleTapFeedbackAllowed(true, false, false, false, false))
        assertFalse(doubleTapFeedbackAllowed(true, true, false, false, false))
    }

    @Test
    fun `切集首次加载与恢复期间不触发双击反馈`() {
        assertFalse(doubleTapFeedbackAllowed(false, false, switching = true, initialLoading = false, restoring = false))
        assertFalse(doubleTapFeedbackAllowed(false, false, switching = false, initialLoading = true, restoring = false))
        assertFalse(doubleTapFeedbackAllowed(false, false, switching = false, initialLoading = false, restoring = true))
        assertTrue(doubleTapFeedbackAllowed(false, false, switching = false, initialLoading = false, restoring = false))
    }

    @Test
    fun `双击暂停与继续不显示任何元素`() {
        assertEquals(emptySet<GestureHint>(), shownGestureHints(controlsVisible = true, GestureHintSource.DoubleTapPause))
        assertEquals(emptySet<GestureHint>(), shownGestureHints(controlsVisible = false, GestureHintSource.DoubleTapPause))
        assertEquals(GestureHintSource.DoubleTapPause, doubleTapHintSource(DoubleTapAction.TogglePlayPause))
    }

    @Test
    fun `双击加减十秒只显示该侧箭头与累计秒数`() {
        assertEquals(
            setOf(GestureHint.DoubleTapSide),
            shownGestureHints(controlsVisible = false, GestureHintSource.DoubleTapSeek),
        )
        // 控制层可见时箭头、秒数与涟漪一起显示。
        assertEquals(
            setOf(GestureHint.DoubleTapSide, GestureHint.DoubleTapRipple),
            shownGestureHints(controlsVisible = true, GestureHintSource.DoubleTapSeek),
        )
        assertEquals(
            GestureHintSource.DoubleTapSeek,
            doubleTapHintSource(DoubleTapAction.Seek(SEEK_STEP_MILLIS, DoubleTapSide.Right)),
        )
    }

    @Test
    fun `控制层隐藏时拖动与滚轮仍显示目标时间与增减量`() {
        assertEquals(
            setOf(GestureHint.SeekTarget),
            shownGestureHints(controlsVisible = false, GestureHintSource.Seek),
        )
        assertEquals(
            setOf(GestureHint.SeekTarget),
            shownGestureHints(controlsVisible = true, GestureHintSource.Seek),
        )
    }

    @Test
    fun `控制层隐藏时音量拖动不显示百分比`() {
        assertEquals(emptySet<GestureHint>(), shownGestureHints(controlsVisible = false, GestureHintSource.Volume))
        assertEquals(
            setOf(GestureHint.VolumePercent),
            shownGestureHints(controlsVisible = true, GestureHintSource.Volume),
        )
    }

    @Test
    fun `隐藏态只保留跳转提示与双击加减十秒的该侧箭头`() {
        GestureHintSource.entries.forEach { source ->
            // 显示规则只由可见性收紧：隐藏时是可见时的子集。
            val shown = shownGestureHints(controlsVisible = true, source)
            val hidden = shownGestureHints(controlsVisible = false, source)
            assertTrue(hidden.all { it in shown })
            assertTrue(
                hidden.all { it == GestureHint.DoubleTapSide || it == GestureHint.SeekTarget },
            )
        }
    }

    @Test
    fun `反馈清空条件覆盖锁定面板加载与恢复`() {
        assertTrue(doubleTapFeedbackBlocked(true, false, false, false, false))
        assertTrue(doubleTapFeedbackBlocked(false, true, false, false, false))
        assertTrue(doubleTapFeedbackBlocked(false, false, true, false, false))
        assertTrue(doubleTapFeedbackBlocked(false, false, false, true, false))
        assertTrue(doubleTapFeedbackBlocked(false, false, false, false, true))
        assertFalse(doubleTapFeedbackBlocked(false, false, false, false, false))
    }
}
