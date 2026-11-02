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

    /** 未锁定、抽屉关闭时，按控制层显隐与首次缓冲判定顶/底栏。 */
    private fun barsVisible(firstBuffering: Boolean = false): Boolean = playerBarsVisible(
        locked = false,
        controlsVisible = controls.visible,
        panelBarsVisible = true,
        firstBuffering = firstBuffering,
    )

    @Test
    fun `控制层隐藏时顶底栏不显示`() {
        controls.toggle()

        assertFalse(barsVisible())
    }

    @Test
    fun `隐藏态切集阶段变化后顶底栏仍不显示`() {
        controls.toggle()
        assertFalse(controls.visible)

        now = 6_000L
        controls.onLoadingPhaseChanged()

        assertFalse(barsVisible())
    }

    @Test
    fun `首次缓冲期顶底栏保持可见`() {
        controls.toggle()
        val firstBuffering = isFirstBuffering(
            initialLoading = true,
            switchedSinceOpen = switchedSinceOpen(switching = false, alreadySwitched = false),
        )

        assertTrue(barsVisible(firstBuffering = firstBuffering))
    }

    @Test
    fun `首次缓冲切过集后不再显示顶底栏`() {
        controls.toggle()
        val switched = switchedSinceOpen(switching = true, alreadySwitched = false)
        val firstBuffering = isFirstBuffering(initialLoading = true, switchedSinceOpen = switched)

        assertFalse(firstBuffering)
        assertFalse(barsVisible(firstBuffering = firstBuffering))
    }

    @Test
    fun `开始过切集后即使切集已结束也不再按首载显示顶底栏`() {
        controls.toggle()
        val switched = switchedSinceOpen(switching = false, alreadySwitched = true)
        assertTrue(switched)

        val firstBuffering = isFirstBuffering(initialLoading = true, switchedSinceOpen = switched)
        assertFalse(firstBuffering)
        assertFalse(barsVisible(firstBuffering = firstBuffering))
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
    fun `控制层隐藏且不在退场时控件不可点击`() {
        controls.toggle()

        assertFalse(
            playerBarsInteractive(
                barsVisible = barsVisible(),
                exitAnimating = false,
            ),
        )
    }

    @Test
    fun `开始淡出时指针立即隐藏`() {
        pointer.onPointerEnter(Offset.Zero)
        now = 3_999L
        controls.hideIfExpired()
        pointerFollowsBars(barsVisible(), pointer)
        assertTrue(pointer.visible)

        now = 4_000L
        controls.hideIfExpired()
        assertFalse(barsVisible())
        // 淡出还要 1 秒才结束，指针不等淡出，随控制层开始隐藏立即消失。
        pointerFollowsBars(barsVisible(), pointer)
        assertFalse(pointer.visible)
    }

    @Test
    fun `淡出期间点击控件重新显示并重置计时`() {
        now = 4_500L
        controls.hideIfExpired()
        assertFalse(controls.visible)

        controls.showByUserAction()
        assertTrue(controls.visible)
        assertEquals(4_000L, controls.millisUntilAutoHide())

        now = 8_499L
        controls.hideIfExpired()
        assertTrue(controls.visible)

        now = 8_500L
        controls.hideIfExpired()
        assertFalse(controls.visible)
    }

    @Test
    fun `指针跟随栏的可见性`() {
        pointer.onPointerEnter(Offset.Zero)

        pointerFollowsBars(barsVisible = false, pointer = pointer)
        assertFalse(pointer.visible)

        pointerFollowsBars(barsVisible = true, pointer = pointer)
        assertTrue(pointer.visible)
    }

    @Test
    fun `锁定或抽屉打开时指针随栏一起隐藏`() {
        pointer.onPointerEnter(Offset.Zero)
        pointerFollowsBars(
            barsVisible = playerBarsVisible(
                locked = true,
                controlsVisible = true,
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
                controlsVisible = true,
                panelBarsVisible = false,
                firstBuffering = false,
            ),
            pointer = pointer,
        )
        assertFalse(pointer.visible)
    }
}
