package com.cc3301.ccplayer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBarsVisibilityTest {

    private var now = 0L
    private val controls = PlayerControlsVisibility(elapsedMillis = { now })
    private val pointer = PlayerPointerVisibility(elapsedMillis = { now })

    /** 生产判据：未锁定、抽屉关闭时的顶/底栏动画目标与在场。 */
    private fun presence(firstBuffering: Boolean = false): PlayerBarsPresence = playerBarsPresence(
        locked = false,
        phase = controls.phase,
        panelBarsVisible = true,
        firstBuffering = firstBuffering,
    )

    @Test
    fun `控制层隐藏时顶底栏不显示`() {
        controls.toggle()

        assertFalse(presence().shown)
        assertFalse(presence().present)
    }

    @Test
    fun `隐藏态切集阶段变化后顶底栏仍不显示`() {
        controls.toggle()
        assertFalse(controls.visible)

        now = 6_000L
        controls.onLoadingPhaseChanged()

        assertFalse(presence().shown)
    }

    @Test
    fun `首次缓冲期顶底栏保持可见`() {
        controls.toggle()
        val firstBuffering = isFirstBuffering(
            initialLoading = true,
            switchedSinceOpen = switchedSinceOpen(switching = false, alreadySwitched = false),
        )

        assertTrue(presence(firstBuffering = firstBuffering).shown)
    }

    @Test
    fun `首次缓冲期单击画面不切换控制层`() {
        assertFalse(toggleControlsAllowed(firstBuffering = true))
        assertTrue(toggleControlsAllowed(firstBuffering = false))
    }

    @Test
    fun `首次缓冲切过集后不再显示顶底栏`() {
        controls.toggle()
        val switched = switchedSinceOpen(switching = true, alreadySwitched = false)
        val firstBuffering = isFirstBuffering(initialLoading = true, switchedSinceOpen = switched)

        assertFalse(firstBuffering)
        assertFalse(presence(firstBuffering = firstBuffering).shown)
    }

    @Test
    fun `开始过切集后即使切集已结束也不再按首载显示顶底栏`() {
        controls.toggle()
        val switched = switchedSinceOpen(switching = false, alreadySwitched = true)
        assertTrue(switched)

        val firstBuffering = isFirstBuffering(initialLoading = true, switchedSinceOpen = switched)
        assertFalse(firstBuffering)
        assertFalse(presence(firstBuffering = firstBuffering).shown)
    }

    @Test
    fun `锁定时顶底栏不显示`() {
        val locked = playerBarsPresence(
            locked = true,
            phase = ControlsPhase.Shown,
            panelBarsVisible = true,
            firstBuffering = true,
        )

        assertFalse(locked.shown)
        assertFalse(locked.present)
    }

    @Test
    fun `抽屉打开时顶底栏不显示`() {
        val drawerOpen = playerBarsPresence(
            locked = false,
            phase = ControlsPhase.Shown,
            panelBarsVisible = false,
            firstBuffering = false,
        )

        assertFalse(drawerOpen.shown)
        assertFalse(drawerOpen.present)
    }

    @Test
    fun `淡出动画期间控件保持可点击`() {
        // 生产判据：未锁定，且栏可见或退场动画未结束（transition 目标为 PostExit）。
        assertTrue(playerBarsInteractive(barsVisible = true, exitAnimating = false, locked = false))
        assertTrue(playerBarsInteractive(barsVisible = false, exitAnimating = true, locked = false))
        assertFalse(playerBarsInteractive(barsVisible = false, exitAnimating = false, locked = false))
    }

    @Test
    fun `锁定后顶底栏退场期间也不可点击`() {
        assertFalse(playerBarsInteractive(barsVisible = false, exitAnimating = true, locked = true))
        assertFalse(playerBarsInteractive(barsVisible = true, exitAnimating = true, locked = true))
    }

    @Test
    fun `控制层隐藏且不在退场时控件不可点击`() {
        controls.toggle()

        assertFalse(
            playerBarsInteractive(
                barsVisible = presence().shown,
                exitAnimating = false,
                locked = false,
            ),
        )
    }

    @Test
    fun `淡出中按下控件撤销淡出并重新计时`() {
        now = 4_200L
        controls.beginFadeOutIfExpired()
        assertTrue(controls.fading)

        // 控件按下走 holdControlsVisible，落到 onPressDown。
        controls.onPressDown()

        assertTrue(controls.visible)
        assertFalse(controls.fading)
        assertEquals(-1L, controls.millisUntilFadeOut())

        controls.onPressUp()
        assertEquals(4_000L, controls.millisUntilFadeOut())
        assertTrue(presence().shown)
    }

    @Test
    fun `手势层按下不动不隐藏控制层`() {
        now = 4_200L
        controls.beginFadeOutIfExpired()

        // 手势层按下即上报按住：手指不动也不进入淡出。
        controls.onGesturePress(true)

        assertTrue(controls.visible)
        assertTrue(presence().shown)

        now = 9_000L
        controls.beginFadeOutIfExpired()
        assertTrue(controls.visible)

        controls.onGesturePress(false)
        assertEquals(4_000L, controls.millisUntilFadeOut())
    }

    @Test
    fun `手势按压计数到最后一指抬起才结束`() {
        val tracker = PlayerGesturePressTracker()

        assertTrue(tracker.onDown(1L))
        assertFalse(tracker.onDown(2L))
        assertTrue(tracker.pressing)

        assertFalse(tracker.onUp(1L))
        assertTrue(tracker.pressing)

        assertTrue(tracker.onUp(2L))
        assertFalse(tracker.pressing)
    }

    @Test
    fun `未按下的指针抬起不算按压结束`() {
        val tracker = PlayerGesturePressTracker()

        assertFalse(tracker.onUp(9L))
        assertFalse(tracker.pressing)
    }

    @Test
    fun `淡出中拖动进度条撤销淡出并重新计时`() {
        now = 4_500L
        controls.beginFadeOutIfExpired()

        // 拖动进度条与滚轮都走 onUserGesture。
        controls.onUserGesture()

        assertTrue(controls.visible)
        assertFalse(controls.fading)
        assertEquals(4_000L, controls.millisUntilFadeOut())
    }

    @Test
    fun `淡出中音量与双击涟漪提示仍显示`() {
        // 淡出从第 4 秒起算，满 1 秒才隐藏：4.999 秒仍在场，5.000 秒才离开。
        now = 4_000L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.fading)

        now = 4_999L
        controls.completeFadeOut()
        val present = presence().present
        assertTrue(present)
        assertTrue(GestureHint.VolumePercent in shownGestureHints(present, GestureHintSource.Volume))
        assertTrue(GestureHint.DoubleTapRipple in shownGestureHints(present, GestureHintSource.DoubleTapSeek))

        now = 5_000L
        controls.completeFadeOut()
        val hidden = presence().present
        assertFalse(hidden)

        assertFalse(GestureHint.VolumePercent in shownGestureHints(hidden, GestureHintSource.Volume))
        assertFalse(GestureHint.DoubleTapRipple in shownGestureHints(hidden, GestureHintSource.DoubleTapSeek))
    }

    @Test
    fun `淡出中指针保持可见第五秒完全隐藏时才消失`() {
        pointer.onPointerEnter()

        now = 4_000L
        controls.beginFadeOutIfExpired()
        pointerFollowsBars(presence().present, pointer)
        assertTrue(pointer.visible)

        now = 5_000L
        controls.completeFadeOut()
        pointerFollowsBars(presence().present, pointer)

        assertFalse(pointer.visible)
    }

    @Test
    fun `涟漪在第五秒完全隐藏后才清理`() {
        val feedback = PlayerDoubleTapFeedback(elapsedMillis = { now })
        feedback.onDoubleTap(DoubleTapSide.Left, 10_000L)
        assertEquals(1, feedback.ripples.size)

        now = 4_000L
        controls.beginFadeOutIfExpired()
        feedback.syncRipples(presence().present)
        assertEquals(1, feedback.ripples.size)

        now = 5_000L
        controls.completeFadeOut()
        feedback.syncRipples(presence().present)
        assertEquals(0, feedback.ripples.size)
    }

    @Test
    fun `指针跟随栏的在场状态`() {
        pointer.onPointerEnter()

        now = 4_000L
        controls.beginFadeOutIfExpired()
        assertTrue(presence().present)
        pointerFollowsBars(presence().present, pointer)
        assertTrue(pointer.visible)

        now = 5_000L
        controls.completeFadeOut()
        pointerFollowsBars(presence().present, pointer)
        assertFalse(pointer.visible)
    }

    @Test
    fun `锁定或抽屉打开时指针随栏一起隐藏`() {
        pointer.onPointerEnter()
        pointerFollowsBars(
            barsPresent = playerBarsPresence(
                locked = true,
                phase = controls.phase,
                panelBarsVisible = true,
                firstBuffering = false,
            ).present,
            pointer = pointer,
        )
        assertFalse(pointer.visible)

        pointer.onPointerEnter()
        pointerFollowsBars(
            barsPresent = playerBarsPresence(
                locked = false,
                phase = controls.phase,
                panelBarsVisible = false,
                firstBuffering = false,
            ).present,
            pointer = pointer,
        )
        assertFalse(pointer.visible)
    }

    @Test
    fun `准备期隐藏标题转屏与底栏但保留更多`() {
        assertFalse(playerTopBarTitleAndRotateVisible(preparing = true))
        assertTrue(actionAllowed(PlayerAction.More, switching = false, initialLoading = true, restoring = false))
        assertFalse(playerBottomBarVisible(barsShown = true, preparing = true))
    }

    @Test
    fun `接手后顶栏与底栏恢复`() {
        assertTrue(playerTopBarTitleAndRotateVisible(preparing = false))
        assertTrue(playerBottomBarVisible(barsShown = true, preparing = false))
        assertFalse(playerBottomBarVisible(barsShown = false, preparing = false))
    }
}
