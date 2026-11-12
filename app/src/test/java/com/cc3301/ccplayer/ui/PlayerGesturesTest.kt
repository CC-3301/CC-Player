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
    fun `左右两侧音量区各占一成，中间为跳转区`() {
        // 音量区固定占屏宽 10%。
        assertEquals(0.10f, VOLUME_ZONE_FRACTION)

        assertEquals(GestureZone.LeftVolume, gestureZone(0f, width))
        assertEquals(GestureZone.LeftVolume, gestureZone(89.9f, width))
        assertEquals(GestureZone.Center, gestureZone(90f, width))
        assertEquals(GestureZone.Center, gestureZone(450f, width))
        assertEquals(GestureZone.Center, gestureZone(809.9f, width))
        assertEquals(GestureZone.RightVolume, gestureZone(810f, width))
        assertEquals(GestureZone.RightVolume, gestureZone(899.9f, width))
    }

    @Test
    fun `左侧双击后退 10 秒`() {
        // 两次点击的间隔窗口固定 300 毫秒。
        assertEquals(300L, DOUBLE_TAP_WINDOW_MILLIS)

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
    fun `双击分区左右各占一成半`() {
        assertEquals(0.15f, DOUBLE_TAP_ZONE_FRACTION)

        assertEquals(DoubleTapAction.Seek(-10_000L, DoubleTapSide.Left), doubleTapAction(134.9f, width))
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(135f, width))
        assertEquals(DoubleTapAction.TogglePlayPause, doubleTapAction(764.9f, width))
        assertEquals(DoubleTapAction.Seek(10_000L, DoubleTapSide.Right), doubleTapAction(765f, width))
    }

    @Test
    fun `音量区比双击区窄，一成到一成半之间双击仍按快退快进`() {
        assertEquals(GestureZone.Center, gestureZone(100f, width))
        assertEquals(DoubleTapAction.Seek(-10_000L, DoubleTapSide.Left), doubleTapAction(100f, width))
        assertEquals(GestureZone.Center, gestureZone(800f, width))
        assertEquals(DoubleTapAction.Seek(10_000L, DoubleTapSide.Right), doubleTapAction(800f, width))
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
        // 进入调整的位移阈值固定 9dp。
        assertEquals(9f, VOLUME_DRAG_THRESHOLD_DP)

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
    fun `加载阶段按表格放行可点操作`() {
        val initialLoading = setOf(
            PlayerAction.Back,
            PlayerAction.More,
            PlayerAction.Subtitles,
            PlayerAction.Volume,
            PlayerAction.Lock,
        )
        val switchingOrRestoring = initialLoading + setOf(
            PlayerAction.Previous,
            PlayerAction.Next,
        )
        data class LoadingCase(
            val name: String,
            val phase: PlayerScreenPhase,
            val allowed: Set<PlayerAction>,
        )
        val cases = listOf(
            LoadingCase("未加载", PlayerScreenPhase.Ready, PlayerAction.entries.toSet()),
            LoadingCase("准备中", PlayerScreenPhase.Preparing, initialLoading),
            LoadingCase("等待接手", PlayerScreenPhase.AwaitingHandover, initialLoading),
            LoadingCase("首次加载", PlayerScreenPhase.Loading, initialLoading),
            LoadingCase("切集", PlayerScreenPhase.Switching, switchingOrRestoring),
            LoadingCase("恢复原片", PlayerScreenPhase.Restoring, switchingOrRestoring),
        )

        cases.forEach { case ->
            PlayerAction.entries.forEach { action ->
                val expected = action in case.allowed
                assertEquals(
                    "${case.name}/${action.name}",
                    expected,
                    actionEnabled(
                        action,
                        interactive = true,
                        canGoPrevious = true,
                        canGoNext = true,
                        phase = case.phase,
                    ),
                )
            }
        }

        // 上一集/下一集在列表边界始终置灰，与加载阶段无关。
        assertFalse(actionEnabled(PlayerAction.Previous, true, false, true, PlayerScreenPhase.Ready))
        assertFalse(actionEnabled(PlayerAction.Next, true, true, false, PlayerScreenPhase.Ready))
        assertTrue(actionEnabled(PlayerAction.Previous, true, true, false, PlayerScreenPhase.Ready))
        assertTrue(actionEnabled(PlayerAction.Next, true, false, true, PlayerScreenPhase.Ready))
    }

    @Test
    fun `栏不可交互时所有操作都置灰且点不动`() {
        PlayerScreenPhase.entries.forEach { phase ->
            PlayerAction.entries.forEach { action ->
                assertFalse(
                    "${phase.name}/${action.name}",
                    actionEnabled(
                        action,
                        interactive = false,
                        canGoPrevious = true,
                        canGoNext = true,
                        phase = phase,
                    ),
                )
            }
        }
    }

    @Test
    fun `旋转进度条与播放列表只在就绪时可用`() {
        listOf(PlayerAction.Rotate, PlayerAction.Seek, PlayerAction.Playlist).forEach { action ->
            PlayerScreenPhase.entries.forEach { phase ->
                assertEquals(
                    "${action.name}/${phase.name}",
                    phase == PlayerScreenPhase.Ready,
                    actionAllowed(action, phase),
                )
            }
        }
    }

    @Test
    fun `切集与恢复期间播放暂停按不可用外观置灰并拦截`() {
        listOf(PlayerScreenPhase.Switching, PlayerScreenPhase.Restoring).forEach { phase ->
            assertFalse(actionAllowed(PlayerAction.PlayPause, phase))
            assertFalse(actionEnabled(PlayerAction.PlayPause, true, true, true, phase))
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
        assertFalse(doubleTapFeedbackAllowed(false, true, PlayerScreenPhase.Ready))
        assertFalse(doubleTapFeedbackAllowed(true, false, PlayerScreenPhase.Ready))
        assertFalse(doubleTapFeedbackAllowed(true, true, PlayerScreenPhase.Ready))
    }

    @Test
    fun `加载阶段期间不触发双击反馈`() {
        assertFalse(doubleTapFeedbackAllowed(false, false, PlayerScreenPhase.Switching))
        assertFalse(doubleTapFeedbackAllowed(false, false, PlayerScreenPhase.Loading))
        assertFalse(doubleTapFeedbackAllowed(false, false, PlayerScreenPhase.Restoring))
        assertTrue(doubleTapFeedbackAllowed(false, false, PlayerScreenPhase.Ready))
    }

    @Test
    fun `双击暂停与继续不显示任何元素`() {
        assertEquals(emptySet<GestureHint>(), shownGestureHints(controlsPresent = true, GestureHintSource.DoubleTapPause))
        assertEquals(emptySet<GestureHint>(), shownGestureHints(controlsPresent = false, GestureHintSource.DoubleTapPause))
        assertEquals(GestureHintSource.DoubleTapPause, doubleTapHintSource(DoubleTapAction.TogglePlayPause))
    }

    @Test
    fun `双击加减十秒只显示该侧箭头与累计秒数`() {
        assertEquals(
            setOf(GestureHint.DoubleTapSide),
            shownGestureHints(controlsPresent = false, GestureHintSource.DoubleTapSeek),
        )
        // 控制层可见时箭头、秒数与涟漪一起显示。
        assertEquals(
            setOf(GestureHint.DoubleTapSide, GestureHint.DoubleTapRipple),
            shownGestureHints(controlsPresent = true, GestureHintSource.DoubleTapSeek),
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
            shownGestureHints(controlsPresent = false, GestureHintSource.Seek),
        )
        assertEquals(
            setOf(GestureHint.SeekTarget),
            shownGestureHints(controlsPresent = true, GestureHintSource.Seek),
        )
    }

    @Test
    fun `控制层隐藏时音量拖动不显示百分比`() {
        assertEquals(emptySet<GestureHint>(), shownGestureHints(controlsPresent = false, GestureHintSource.Volume))
        assertEquals(
            setOf(GestureHint.VolumePercent),
            shownGestureHints(controlsPresent = true, GestureHintSource.Volume),
        )
    }

    @Test
    fun `隐藏态只保留跳转提示与双击加减十秒的该侧箭头`() {
        GestureHintSource.entries.forEach { source ->
            // 显示规则只由可见性收紧：隐藏时是可见时的子集。
            val shown = shownGestureHints(controlsPresent = true, source)
            val hidden = shownGestureHints(controlsPresent = false, source)
            assertTrue(hidden.all { it in shown })
            assertTrue(
                hidden.all { it == GestureHint.DoubleTapSide || it == GestureHint.SeekTarget },
            )
        }
    }

    @Test
    fun `反馈清空条件覆盖锁定面板与各加载阶段`() {
        assertTrue(doubleTapFeedbackBlocked(true, false, PlayerScreenPhase.Ready))
        assertTrue(doubleTapFeedbackBlocked(false, true, PlayerScreenPhase.Ready))
        assertTrue(doubleTapFeedbackBlocked(false, false, PlayerScreenPhase.Switching))
        assertTrue(doubleTapFeedbackBlocked(false, false, PlayerScreenPhase.Loading))
        assertTrue(doubleTapFeedbackBlocked(false, false, PlayerScreenPhase.Restoring))
        assertFalse(doubleTapFeedbackBlocked(false, false, PlayerScreenPhase.Ready))
    }
}
