package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLockTest {

    private var now = 0L
    private val lock = PlayerLock(elapsedMillis = { now })

    @Test
    fun `默认未锁定`() {
        assertEquals(false, lock.locked)
        assertEquals(false, lock.unlockPending)
    }

    @Test
    fun `第一次点锁按钮进入锁定`() {
        lock.tapLockButton()

        assertEquals(true, lock.locked)
        assertEquals(false, lock.unlockPending)
    }

    @Test
    fun `锁定后点一次只是进入确认态`() {
        lock.tapLockButton()
        now = 1_000L

        lock.tapLockButton()

        assertEquals(true, lock.locked)
        assertEquals(true, lock.unlockPending)
    }

    @Test
    fun `确认期内再点一次才解锁`() {
        lock.tapLockButton()
        now = 1_000L
        lock.tapLockButton()
        now = 3_999L

        lock.tapLockButton()

        assertEquals(false, lock.locked)
        assertEquals(false, lock.unlockPending)
    }

    @Test
    fun `过了确认期再点不解锁而是重新进入确认态`() {
        lock.tapLockButton()
        now = 1_000L
        lock.tapLockButton()
        now = 4_001L

        lock.tapLockButton()

        assertEquals(true, lock.locked)
        assertEquals(true, lock.unlockPending)
    }

    @Test
    fun `恰好到确认期边界视为超时`() {
        lock.tapLockButton()
        now = 1_000L
        lock.tapLockButton()
        now = 4_000L

        lock.tapLockButton()

        assertEquals(true, lock.locked)
        assertEquals(true, lock.unlockPending)
    }

    @Test
    fun `确认期从最后一次点击重新计时`() {
        lock.tapLockButton()
        now = 1_000L
        lock.tapLockButton()
        now = 4_001L
        lock.tapLockButton()
        now = 7_000L

        lock.tapLockButton()

        assertEquals(false, lock.locked)
    }

    @Test
    fun `确认态超时后回到纯锁定态`() {
        lock.tapLockButton()
        now = 1_000L
        lock.tapLockButton()

        lock.expireUnlock()

        assertEquals(true, lock.locked)
        assertEquals(false, lock.unlockPending)
    }

    @Test
    fun `解锁后再点锁按钮重新锁定`() {
        lock.tapLockButton()
        now = 1_000L
        lock.tapLockButton()
        now = 2_000L
        lock.tapLockButton()

        lock.tapLockButton()

        assertEquals(true, lock.locked)
        assertEquals(false, lock.unlockPending)
    }

    @Test
    fun `锁定时手势与控制条都不放行只留解锁按钮`() {
        lock.tapLockButton()

        assertEquals(false, inputAllowed(PlayerInput.DoubleTap, lock.locked))
        assertEquals(false, inputAllowed(PlayerInput.LevelDrag, lock.locked))
        assertEquals(false, inputAllowed(PlayerInput.ControlBar, lock.locked))
        assertEquals(true, inputAllowed(PlayerInput.UnlockButton, lock.locked))
    }
}
