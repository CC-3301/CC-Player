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

class PlayerLockTest {

    private var now = 0L
    private val lock = PlayerLock(elapsedMillis = { now })

    @Test
    fun `默认未锁定且解锁按钮隐藏`() {
        assertEquals(false, lock.locked)
        assertEquals(false, lock.unlockVisible)
    }

    @Test
    fun `未锁定时点锁定键立即上锁并显示解锁按钮`() {
        lock.tapLockButton()

        assertEquals(true, lock.locked)
        assertEquals(true, lock.unlockVisible)
    }

    @Test
    fun `锁定后点一次解锁按钮立即解锁`() {
        lock.tapLockButton()
        now = 1_000L

        lock.tapLockButton()

        assertEquals(false, lock.locked)
        assertEquals(false, lock.unlockVisible)
    }

    @Test
    fun `解锁动作立即移除按钮`() {
        lock.tapLockButton()
        assertEquals(true, lock.unlockVisible)

        lock.tapLockButton()

        assertEquals(false, lock.locked)
        assertEquals(false, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)
    }

    @Test
    fun `解锁按钮四秒开始淡出五秒完全消失`() {
        // 与控制层同一套计时：4 秒开始淡出、5 秒完全消失。
        assertEquals(UNLOCK_FADE_START_MILLIS, CONTROLS_FADE_START_MILLIS)
        assertEquals(UNLOCK_FADE_OUT_MILLIS, CONTROLS_FADE_OUT_MILLIS)

        lock.tapLockButton()

        now = 3_999L
        lock.beginUnlockFadeOutIfExpired()
        assertEquals(true, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)

        now = 4_000L
        lock.beginUnlockFadeOutIfExpired()
        assertEquals(true, lock.unlockVisible)
        assertEquals(true, lock.unlockFading)

        now = 4_999L
        lock.completeUnlockFadeOut()
        assertEquals(true, lock.unlockVisible)

        now = 5_000L
        lock.completeUnlockFadeOut()

        assertEquals(true, lock.locked)
        assertEquals(false, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)
    }

    @Test
    fun `淡出中解锁按钮点一次仍可解锁`() {
        lock.tapLockButton()
        now = 4_500L
        lock.beginUnlockFadeOutIfExpired()
        assertEquals(true, lock.unlockFading)

        lock.tapLockButton()

        assertEquals(false, lock.locked)
        assertEquals(false, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)
    }

    @Test
    fun `按住解锁按钮期间不进入淡出`() {
        lock.tapLockButton()
        lock.onPressDown()

        now = 30_000L
        lock.beginUnlockFadeOutIfExpired()

        assertEquals(true, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)

        lock.onPressUp()
        assertEquals(4_000L, lock.millisUntilUnlockFadeOut())
    }

    @Test
    fun `淡出中按下解锁按钮撤销淡出`() {
        lock.tapLockButton()
        now = 4_500L
        lock.beginUnlockFadeOutIfExpired()
        assertEquals(true, lock.unlockFading)

        lock.onPressDown()

        assertEquals(true, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)
        assertEquals(true, lock.unlockPressing)

        lock.onPressUp()
        assertEquals(false, lock.unlockPressing)
    }

    @Test
    fun `锁定态点画面重新显示解锁按钮`() {
        lock.tapLockButton()
        now = 4_000L
        lock.beginUnlockFadeOutIfExpired()
        now = 5_000L
        lock.completeUnlockFadeOut()
        now = 7_000L

        lock.tapScreen()

        assertEquals(true, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)
    }

    @Test
    fun `点画面重新显示后自动隐藏重新计时`() {
        lock.tapLockButton()
        now = 4_000L
        lock.beginUnlockFadeOutIfExpired()
        now = 5_000L
        lock.completeUnlockFadeOut()
        now = 7_000L
        lock.tapScreen()

        now = 10_999L
        lock.beginUnlockFadeOutIfExpired()
        assertEquals(true, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)

        now = 11_000L
        lock.beginUnlockFadeOutIfExpired()
        assertEquals(true, lock.unlockFading)

        now = 11_999L
        lock.completeUnlockFadeOut()
        assertEquals(true, lock.unlockVisible)

        now = 12_000L
        lock.completeUnlockFadeOut()
        assertEquals(false, lock.unlockVisible)
    }

    @Test
    fun `未锁定时点画面不显示解锁按钮`() {
        lock.tapScreen()

        assertEquals(false, lock.locked)
        assertEquals(false, lock.unlockVisible)
    }

    @Test
    fun `自动隐藏后仍保持锁定`() {
        lock.tapLockButton()
        now = 4_000L
        lock.beginUnlockFadeOutIfExpired()
        now = 5_000L

        lock.completeUnlockFadeOut()

        assertEquals(true, lock.locked)
    }

    @Test
    fun `解锁后重新上锁再次显示解锁按钮`() {
        lock.tapLockButton()
        now = 1_000L
        lock.tapLockButton()
        now = 2_000L
        lock.tapLockButton()

        assertEquals(true, lock.locked)
        assertEquals(true, lock.unlockVisible)

        now = 5_999L
        lock.beginUnlockFadeOutIfExpired()
        assertEquals(true, lock.unlockVisible)
        assertEquals(false, lock.unlockFading)

        now = 6_000L
        lock.beginUnlockFadeOutIfExpired()
        assertEquals(true, lock.unlockFading)

        now = 6_999L
        lock.completeUnlockFadeOut()
        assertEquals(true, lock.unlockVisible)

        now = 7_000L
        lock.completeUnlockFadeOut()
        assertEquals(false, lock.unlockVisible)
    }

    @Test
    fun `解锁按钮自动隐藏循环到点淡出并在五秒消失`() = runTest {
        val looped = PlayerLock(elapsedMillis = { testScheduler.currentTime })
        looped.tapLockButton()
        val loop = launch {
            runUnlockAutoHide(looped, delayMillis = { delay(it) })
        }

        advanceTimeBy(3_999L)
        runCurrent()
        assertEquals(true, looped.unlockVisible)
        assertEquals(false, looped.unlockFading)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(true, looped.unlockFading)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(false, looped.unlockVisible)

        loop.cancel()
    }

    @Test
    fun `解锁按钮等待提前返回时循环重新评估`() = runTest {
        val looped = PlayerLock(elapsedMillis = { testScheduler.currentTime })
        looped.tapLockButton()
        val loop = launch {
            runUnlockAutoHide(looped, delayMillis = { delay(it / 2 + 1) })
        }

        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(true, looped.unlockFading)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(false, looped.unlockVisible)

        loop.cancel()
    }

    @Test
    fun `锁定时手势与控制条都不放行只留解锁按钮`() {
        lock.tapLockButton()

        assertEquals(false, inputAllowed(PlayerInput.Tap, lock.locked))
        assertEquals(false, inputAllowed(PlayerInput.DoubleTap, lock.locked))
        assertEquals(false, inputAllowed(PlayerInput.VolumeDrag, lock.locked))
        assertEquals(false, inputAllowed(PlayerInput.SeekDrag, lock.locked))
        assertEquals(false, inputAllowed(PlayerInput.ControlBar, lock.locked))
        assertEquals(true, inputAllowed(PlayerInput.UnlockButton, lock.locked))
    }
}
