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

    /** 未锁定、抽屉关闭时，顶/底栏的动画目标：控制层完全可见才显示。 */
    private fun barsShown(firstBuffering: Boolean = false): Boolean = playerBarsVisible(
        locked = false,
        controlsVisible = controls.visible,
        panelBarsVisible = true,
        firstBuffering = firstBuffering,
    )

    /** 顶/底栏是否在场（可见或淡出中）：指针与退场可点按此判定。 */
    private fun barsPresent(firstBuffering: Boolean = false): Boolean = playerBarsVisible(
        locked = false,
        controlsVisible = !controls.hidden,
        panelBarsVisible = true,
        firstBuffering = firstBuffering,
    )

    @Test
    fun `控制层隐藏时顶底栏不显示`() {
        controls.toggle()

        assertFalse(barsShown())
    }

    @Test
    fun `隐藏态切集阶段变化后顶底栏仍不显示`() {
        controls.toggle()
        assertFalse(controls.visible)

        now = 6_000L
        controls.onLoadingPhaseChanged()

        assertFalse(barsShown())
    }

    @Test
    fun `首次缓冲期顶底栏保持可见`() {
        controls.toggle()
        val firstBuffering = isFirstBuffering(
            initialLoading = true,
            switchedSinceOpen = switchedSinceOpen(switching = false, alreadySwitched = false),
        )

        assertTrue(barsShown(firstBuffering = firstBuffering))
    }

    @Test
    fun `首次缓冲切过集后不再显示顶底栏`() {
        controls.toggle()
        val switched = switchedSinceOpen(switching = true, alreadySwitched = false)
        val firstBuffering = isFirstBuffering(initialLoading = true, switchedSinceOpen = switched)

        assertFalse(firstBuffering)
        assertFalse(barsShown(firstBuffering = firstBuffering))
    }

    @Test
    fun `开始过切集后即使切集已结束也不再按首载显示顶底栏`() {
        controls.toggle()
        val switched = switchedSinceOpen(switching = false, alreadySwitched = true)
        assertTrue(switched)

        val firstBuffering = isFirstBuffering(initialLoading = true, switchedSinceOpen = switched)
        assertFalse(firstBuffering)
        assertFalse(barsShown(firstBuffering = firstBuffering))
    }

    @Test
    fun `锁定时顶底栏不显示`() {
        assertFalse(
            playerBarsVisible(
                locked = true,
                controlsVisible = true,
                panelBarsVisible = true,
                firstBuffering = true,
            ),
        )
    }

    @Test
    fun `抽屉打开时顶底栏不显示`() {
        assertFalse(
            playerBarsVisible(
                locked = false,
                controlsVisible = true,
                panelBarsVisible = false,
                firstBuffering = false,
            ),
        )
    }

    @Test
    fun `淡出动画期间控件保持可点击`() {
        assertTrue(playerBarsInteractive(barsVisible = true, exitAnimating = false))
        // 栏已不可见但淡出未结束：画面还在，点击照常生效。
        assertTrue(playerBarsInteractive(barsVisible = false, exitAnimating = true))
        assertFalse(playerBarsInteractive(barsVisible = false, exitAnimating = false))
    }

    @Test
    fun `淡出窗口内控件保持可点击`() {
        now = 4_500L
        controls.beginFadeOutIfExpired()

        assertTrue(controls.fading)
        assertTrue(barsPresent())
        assertFalse(barsShown())
        assertTrue(playerBarsInteractive(barsVisible = barsShown(), exitAnimating = controls.fading))
    }

    @Test
    fun `控制层隐藏且不在退场时控件不可点击`() {
        controls.toggle()

        assertFalse(
            playerBarsInteractive(
                barsVisible = barsShown(),
                exitAnimating = false,
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
        assertTrue(barsShown())
    }

    @Test
    fun `淡出中拖动进度条撤销淡出并重新计时`() {
        now = 4_500L
        controls.beginFadeOutIfExpired()

        // 拖动、滚轮与手势都只走 resetTimer。
        controls.resetTimer()

        assertTrue(controls.visible)
        assertFalse(controls.fading)
        assertEquals(4_000L, controls.millisUntilFadeOut())
    }

    @Test
    fun `淡出中指针保持可见第五秒完全隐藏时才消失`() {
        pointer.onPointerEnter(Offset.Zero)

        now = 4_000L
        controls.beginFadeOutIfExpired()
        pointerFollowsBars(barsPresent(), pointer)
        assertTrue(pointer.visible)

        now = 5_000L
        controls.completeFadeOut()
        pointerFollowsBars(barsPresent(), pointer)

        assertFalse(pointer.visible)
    }

    @Test
    fun `涟漪在第五秒完全隐藏后才清理`() {
        now = 4_000L
        controls.beginFadeOutIfExpired()
        // 淡出中仍保留子树与涟漪。
        assertFalse(controls.hidden)

        now = 4_999L
        controls.completeFadeOut()
        assertFalse(controls.hidden)

        now = 5_000L
        controls.completeFadeOut()
        assertTrue(controls.hidden)
    }

    @Test
    fun `指针跟随栏的在场状态`() {
        pointer.onPointerEnter(Offset.Zero)

        controls.toggle()
        pointerFollowsBars(barsPresent(), pointer)
        assertTrue(pointer.visible)

        now = 5_000L
        controls.completeFadeOut()
        pointerFollowsBars(barsPresent(), pointer)
        assertFalse(pointer.visible)
    }

    @Test
    fun `锁定或抽屉打开时指针随栏一起隐藏`() {
        pointer.onPointerEnter(Offset.Zero)
        pointerFollowsBars(
            barsVisible = playerBarsVisible(
                locked = true,
                controlsVisible = !controls.hidden,
                panelBarsVisible = true,
                firstBuffering = false,
            ),
            pointer = pointer,
        )
        assertFalse(pointer.visible)

        pointer.onPointerEnter(Offset.Zero)
        pointerFollowsBars(
            barsVisible = playerBarsVisible(
                locked = false,
                controlsVisible = !controls.hidden,
                panelBarsVisible = false,
                firstBuffering = false,
            ),
            pointer = pointer,
        )
        assertFalse(pointer.visible)
    }
}
