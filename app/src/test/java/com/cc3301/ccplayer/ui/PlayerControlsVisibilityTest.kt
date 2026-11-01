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
    fun `五秒无操作后隐藏控制层`() {
        now = 4_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 5_000L
        controls.hideIfExpired()

        assertEquals(false, controls.visible)
    }

    @Test
    fun `控制层淡入淡出为 200 毫秒`() {
        assertEquals(200L, CONTROLS_FADE_MILLIS)
    }

    @Test
    fun `点击切换控制层显隐`() {
        controls.toggle()
        assertEquals(false, controls.visible)

        controls.toggle()
        assertEquals(true, controls.visible)
    }

    @Test
    fun `操作后重新计时`() {
        now = 2_000L
        controls.show()

        now = 6_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 7_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `隐藏后操作重新显示控制层`() {
        now = 5_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)

        controls.show()

        assertEquals(true, controls.visible)
    }

    @Test
    fun `隐藏时手势只重置计时不唤出控制层`() {
        now = 5_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)

        now = 6_000L
        controls.resetTimer()

        assertEquals(false, controls.visible)
    }

    @Test
    fun `显示时手势重置计时但不改变显隐`() {
        now = 1_000L
        controls.show()

        now = 5_999L
        controls.resetTimer()

        assertEquals(true, controls.visible)

        now = 10_998L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 10_999L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `剩余时间按最近操作计算`() {
        assertEquals(5_000L, controls.millisUntilAutoHide())

        now = 1_000L
        assertEquals(4_000L, controls.millisUntilAutoHide())

        controls.show()
        assertEquals(5_000L, controls.millisUntilAutoHide())

        now = 3_500L
        assertEquals(2_500L, controls.millisUntilAutoHide())
    }

    @Test
    fun `面板打开时不计算自动隐藏时间`() {
        controls.updatePanelOpen(true)

        assertEquals(-1L, controls.millisUntilAutoHide())
    }

    @Test
    fun `隐藏后不再计算自动隐藏时间`() {
        now = 5_000L
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

        now = 14_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 15_000L
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
    fun `隐藏态加载阶段变化不显示控制层`() {
        now = 5_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)

        now = 6_000L
        controls.onLoadingPhaseChanged()

        assertEquals(false, controls.visible)
    }

    @Test
    fun `显示态加载阶段变化重新计时`() {
        now = 4_000L
        controls.onLoadingPhaseChanged()

        now = 8_999L
        controls.hideIfExpired()
        assertEquals(true, controls.visible)

        now = 9_000L
        controls.hideIfExpired()
        assertEquals(false, controls.visible)
    }
}
