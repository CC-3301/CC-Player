package com.cc3301.ccplayer.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlsVisibilityTest {

    private var now = 0L
    private val controls = PlayerControlsVisibility(elapsedMillis = { now })

    @Test
    fun `默认显示控制层`() {
        assertEquals(true, controls.visible)
        assertEquals(false, controls.fading)
        assertEquals(false, controls.hidden)
    }

    @Test
    fun `四秒无操作后开始淡出控制层`() {
        now = 3_999L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        now = 4_000L
        controls.beginFadeOutIfExpired()

        assertEquals(false, controls.visible)
        assertEquals(true, controls.fading)
        assertEquals(false, controls.hidden)
    }

    @Test
    fun `五秒后完全隐藏控制层`() {
        now = 4_000L
        controls.beginFadeOutIfExpired()

        now = 4_999L
        controls.completeFadeOut()
        assertEquals(true, controls.fading)
        assertEquals(false, controls.hidden)

        now = 5_000L
        controls.completeFadeOut()

        assertEquals(true, controls.hidden)
        assertEquals(false, controls.fading)
    }

    @Test
    fun `点击切换控制层显隐`() {
        controls.toggle()
        assertEquals(false, controls.visible)
        assertEquals(true, controls.fading)

        controls.toggle()
        assertEquals(true, controls.visible)
        assertEquals(false, controls.fading)
    }

    @Test
    fun `淡出中点击画面撤销淡出`() {
        now = 4_500L
        controls.beginFadeOutIfExpired()

        controls.toggle()

        assertEquals(true, controls.visible)
        assertEquals(false, controls.fading)
    }

    @Test
    fun `按住期间点画面不进入淡出`() {
        // 一指按住进度条，另一指点画面：任何路径都不得进入淡出。
        controls.onPressDown()

        controls.toggle()

        assertEquals(true, controls.visible)
        assertEquals(false, controls.fading)

        now = 10_000L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        controls.onPressUp()
        assertEquals(4_000L, controls.millisUntilFadeOut())
    }

    @Test
    fun `操作后重新计时`() {
        now = 2_000L
        controls.showByUserAction()

        now = 5_999L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        now = 6_000L
        controls.beginFadeOutIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `隐藏后操作重新显示控制层`() {
        now = 4_000L
        controls.beginFadeOutIfExpired()
        now = 5_000L
        controls.completeFadeOut()
        assertEquals(true, controls.hidden)

        controls.showByUserAction()

        assertEquals(true, controls.visible)
        assertEquals(false, controls.hidden)
    }

    @Test
    fun `淡出中按下控件撤销淡出并重新计时`() {
        now = 4_200L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.fading)

        // 控件按下走 holdControlsVisible，落到 onPressDown。
        controls.onPressDown()

        assertEquals(true, controls.visible)
        assertEquals(false, controls.fading)
        assertEquals(true, controls.pressing)
        assertEquals(-1L, controls.millisUntilFadeOut())

        // 按住期间不进入淡出。
        now = 9_000L
        assertEquals(-1L, controls.millisUntilFadeOut())
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        // 抬起后从抬起时刻重新计时。
        controls.onPressUp()
        assertEquals(4_000L, controls.millisUntilFadeOut())

        now = 12_999L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        now = 13_000L
        controls.beginFadeOutIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `淡出中拖动或滚轮撤销淡出并重新计时`() {
        now = 4_500L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.fading)

        // 拖动进度条、滚轮与整屏手势都走 onUserGesture。
        controls.onUserGesture()

        assertEquals(true, controls.visible)
        assertEquals(false, controls.fading)
        assertEquals(4_500L, controls.lastInteractionAtMillis)
        assertEquals(4_000L, controls.millisUntilFadeOut())
    }

    @Test
    fun `淡出结束后手势只重置计时不唤出控制层`() {
        now = 4_000L
        controls.beginFadeOutIfExpired()
        now = 5_000L
        controls.completeFadeOut()

        now = 6_000L
        controls.onUserGesture()

        assertEquals(false, controls.visible)
        assertEquals(true, controls.hidden)
    }

    @Test
    fun `显示时手势重置计时但不改变显隐`() {
        now = 1_000L
        controls.showByUserAction()

        now = 4_999L
        controls.onUserGesture()

        assertEquals(true, controls.visible)

        now = 8_998L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        now = 8_999L
        controls.beginFadeOutIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `剩余时间按最近操作计算`() {
        assertEquals(4_000L, controls.millisUntilFadeOut())

        now = 1_000L
        assertEquals(3_000L, controls.millisUntilFadeOut())

        controls.showByUserAction()
        assertEquals(4_000L, controls.millisUntilFadeOut())

        now = 3_500L
        assertEquals(1_500L, controls.millisUntilFadeOut())
    }

    @Test
    fun `面板打开时不计算自动隐藏时间`() {
        controls.updatePanelOpen(true)

        assertEquals(-1L, controls.millisUntilFadeOut())
    }

    @Test
    fun `隐藏后不再计算自动隐藏时间`() {
        now = 4_000L
        controls.beginFadeOutIfExpired()
        now = 5_000L
        controls.completeFadeOut()

        assertEquals(-1L, controls.millisUntilFadeOut())
    }

    @Test
    fun `面板打开时不隐藏`() {
        controls.updatePanelOpen(true)

        now = 10_000L
        controls.beginFadeOutIfExpired()

        assertEquals(true, controls.visible)
    }

    @Test
    fun `面板关闭后重新计时`() {
        controls.updatePanelOpen(true)
        now = 10_000L
        controls.updatePanelOpen(false)

        now = 13_999L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        now = 14_000L
        controls.beginFadeOutIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `加载期间不自动隐藏`() {
        now = 10_000L

        assertEquals(-1L, controls.millisUntilFadeOut(holdVisible = true))
        controls.beginFadeOutIfExpired(holdVisible = true)

        assertEquals(true, controls.visible)
    }

    @Test
    fun `按下控件即重新计时`() {
        now = 3_999L
        controls.onPressDown()

        assertEquals(3_999L, controls.lastInteractionAtMillis)

        now = 4_000L
        controls.beginFadeOutIfExpired()

        assertEquals(true, controls.visible)
    }

    @Test
    fun `按住期间不自动隐藏`() {
        controls.onPressDown()
        assertEquals(true, controls.pressing)

        now = 30_000L
        assertEquals(-1L, controls.millisUntilFadeOut())
        controls.beginFadeOutIfExpired()

        assertEquals(true, controls.visible)
    }

    @Test
    fun `多指按住时先抬起一指仍算按住`() {
        controls.onPressDown()
        controls.onPressDown()

        controls.onPressUp()
        assertEquals(true, controls.pressing)
        now = 30_000L
        assertEquals(-1L, controls.millisUntilFadeOut())
        controls.beginFadeOutIfExpired()
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
        assertEquals(4_000L, controls.millisUntilFadeOut())

        now = 23_999L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        now = 24_000L
        controls.beginFadeOutIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `隐藏态加载阶段变化不显示控制层`() {
        now = 4_000L
        controls.beginFadeOutIfExpired()
        now = 5_000L
        controls.completeFadeOut()
        assertEquals(true, controls.hidden)

        now = 6_000L
        controls.onLoadingPhaseChanged()

        assertEquals(false, controls.visible)
        assertEquals(true, controls.hidden)
    }

    @Test
    fun `显示态加载阶段变化重新计时`() {
        now = 4_000L
        controls.onLoadingPhaseChanged()

        now = 7_999L
        controls.beginFadeOutIfExpired()
        assertEquals(true, controls.visible)

        now = 8_000L
        controls.beginFadeOutIfExpired()
        assertEquals(false, controls.visible)
    }

    @Test
    fun `自动隐藏循环到点淡出并在五秒隐藏`() = runTest {
        val looped = PlayerControlsVisibility(elapsedMillis = { testScheduler.currentTime })
        val loop = launch {
            runControlsAutoHide(looped, holdVisible = { false }, delayMillis = { delay(it) })
        }

        advanceTimeBy(3_999L)
        runCurrent()
        assertEquals(true, looped.visible)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(true, looped.fading)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(true, looped.hidden)

        loop.cancel()
    }

    @Test
    fun `等待提前返回时自动隐藏循环重新评估`() = runTest {
        val looped = PlayerControlsVisibility(elapsedMillis = { testScheduler.currentTime })
        val loop = launch {
            // 每次只等一半左右：模拟提前唤醒，循环必须重新评估并继续等到点。
            runControlsAutoHide(looped, holdVisible = { false }, delayMillis = { delay(it / 2 + 1) })
        }

        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(true, looped.fading)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(true, looped.hidden)

        loop.cancel()
    }
}
