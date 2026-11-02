package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlsVisibilityTest {

    private var now = 0L
    private val controls = PlayerControlsVisibility(elapsedMillis = { now })

    @Test
    fun `默认显示控制层`() {
        assertEquals(true, controls.visible)
    }

    @Test
    fun `四秒无操作后开始淡出控制层`() {
        now = 3_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 4_000L
        controls.hideIfExpired()

        assertEquals(false, controls.visible)
    }

    @Test
    fun `四秒开始淡出五秒完全消失`() {
        // 淡出 1 秒：开始淡出时刻加上淡出时长等于完全消失的时刻。
        assertEquals(1_000L, CONTROLS_FADE_OUT_MILLIS)
        assertEquals(4_000L, CONTROLS_FADE_START_MILLIS)
        assertEquals(5_000L, CONTROLS_FADE_START_MILLIS + CONTROLS_FADE_OUT_MILLIS)
        assertEquals(5_000L, CONTROLS_AUTO_HIDE_MILLIS)
    }

    @Test
    fun `点击切换控制层显隐`() {
        // 显示固定 200 毫秒淡入，隐藏先转状态再淡出 1 秒。
        assertEquals(200L, CONTROLS_FADE_IN_MILLIS)

        controls.toggle()
        assertEquals(false, controls.visible)

        controls.toggle()
        assertEquals(true, controls.visible)
    }

    @Test
    fun `操作后重新计时`() {
        now = 2_000L
        controls.showByUserAction()

        now = 5_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 6_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `隐藏后操作重新显示控制层`() {
        now = 4_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)

        controls.showByUserAction()

        assertEquals(true, controls.visible)
    }

    @Test
    fun `隐藏时手势只重置计时不唤出控制层`() {
        now = 4_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)

        now = 5_000L
        controls.resetTimer()

        assertEquals(false, controls.visible)
    }

    @Test
    fun `显示时手势重置计时但不改变显隐`() {
        now = 1_000L
        controls.showByUserAction()

        now = 4_999L
        controls.resetTimer()

        assertEquals(true, controls.visible)

        now = 8_998L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 8_999L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `剩余时间按最近操作计算`() {
        assertEquals(4_000L, controls.millisUntilAutoHide())

        now = 1_000L
        assertEquals(3_000L, controls.millisUntilAutoHide())

        controls.showByUserAction()
        assertEquals(4_000L, controls.millisUntilAutoHide())

        now = 3_500L
        assertEquals(1_500L, controls.millisUntilAutoHide())
    }

    @Test
    fun `面板打开时不计算自动隐藏时间`() {
        controls.updatePanelOpen(true)

        assertEquals(-1L, controls.millisUntilAutoHide())
    }

    @Test
    fun `隐藏后不再计算自动隐藏时间`() {
        now = 4_000L
        controls.hideIfExpired()

        assertEquals(-1L, controls.millisUntilAutoHide())
    }

    @Test
    fun `面板打开时不隐藏`() {
        controls.updatePanelOpen(true)

        now = 10_000L
        controls.hideIfExpired()

        assertEquals(true, controls.visible)
    }

    @Test
    fun `面板关闭后重新计时`() {
        controls.updatePanelOpen(true)
        now = 10_000L
        controls.updatePanelOpen(false)

        now = 13_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 14_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `加载期间不自动隐藏`() {
        now = 10_000L

        assertEquals(-1L, controls.millisUntilAutoHide(holdVisible = true))
        controls.hideIfExpired(holdVisible = true)

        assertEquals(true, controls.visible)
    }

    @Test
    fun `按下控件即重新计时`() {
        now = 3_999L
        controls.onPressDown()

        assertEquals(3_999L, controls.lastInteractionAtMillis)

        now = 4_000L
        controls.hideIfExpired()

        assertEquals(true, controls.visible)
    }

    @Test
    fun `按住期间不自动隐藏`() {
        controls.onPressDown()
        assertEquals(true, controls.pressing)

        now = 30_000L
        assertEquals(-1L, controls.millisUntilAutoHide())
        controls.hideIfExpired()

        assertEquals(true, controls.visible)
    }

    @Test
    fun `多指按住时先抬起一指仍算按住`() {
        controls.onPressDown()
        controls.onPressDown()

        controls.onPressUp()
        assertEquals(true, controls.pressing)
        now = 30_000L
        assertEquals(-1L, controls.millisUntilAutoHide())
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        // 最后一指抬起才恢复计时。
        controls.onPressUp()
        assertEquals(false, controls.pressing)
        assertEquals(30_000L, controls.lastInteractionAtMillis)
    }

    @Test
    fun `抬起后从抬起时刻重新计时`() {
        controls.onPressDown()
        now = 20_000L
        controls.onPressUp()

        assertEquals(false, controls.pressing)
        assertEquals(4_000L, controls.millisUntilAutoHide())

        now = 23_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 24_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `隐藏态加载阶段变化不显示控制层`() {
        now = 4_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)

        now = 5_000L
        controls.onLoadingPhaseChanged()

        assertEquals(false, controls.visible)
    }

    @Test
    fun `显示态加载阶段变化重新计时`() {
        now = 4_000L
        controls.onLoadingPhaseChanged()

        now = 7_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 8_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)
    }
}
