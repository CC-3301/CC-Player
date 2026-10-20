package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
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
    fun `解锁按钮显示三秒后自动隐藏`() {
        lock.tapLockButton()
        now = 2_999L
        lock.expireUnlock()
        assertEquals(true, lock.unlockVisible)

        now = 3_000L
        lock.expireUnlock()

        assertEquals(true, lock.locked)
        assertEquals(false, lock.unlockVisible)
    }

    @Test
    fun `锁定态点画面重新显示解锁按钮`() {
        lock.tapLockButton()
        now = 3_000L
        lock.expireUnlock()
        now = 5_000L

        lock.tapScreen()

        assertEquals(true, lock.unlockVisible)
    }

    @Test
    fun `点画面重新显示后自动隐藏重新计时`() {
        lock.tapLockButton()
        now = 3_000L
        lock.expireUnlock()
        now = 5_000L
        lock.tapScreen()

        now = 7_999L
        lock.expireUnlock()
        assertEquals(true, lock.unlockVisible)

        now = 8_000L
        lock.expireUnlock()
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
        now = 3_000L

        lock.expireUnlock()

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
        assertEquals(2_000L, lock.unlockShownAtMillis)
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
