package com.cc3301.ccplayer.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首次摆位等方向的时限：到点无条件放行，方向是否对齐都不再等。 */
class PlayerOrientationWaitTest {

    @Test
    fun `方向等待的时限是 2 秒`() {
        assertEquals(2_000L, PLAYER_ORIENTATION_WAIT_LIMIT_MILLIS)
    }

    @Test
    fun `等满时限才放行`() = runTest {
        var released = false
        launch {
            runOrientationWait()
            released = true
        }

        advanceTimeBy(PLAYER_ORIENTATION_WAIT_LIMIT_MILLIS - 1)
        runCurrent()
        assertFalse(released)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(released)
    }

    @Test
    fun `一直不对齐时等满时限摆位并撤黑`() = runTest {
        var gate = PlayerPlacementGate().withFrame(
            ratioKnown = true,
            showsPicture = true,
            rotationPending = true,
        )
        assertTrue(gate.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = true))

        launch {
            runOrientationWait()
            gate = gate.withWaitLimit()
        }

        advanceTimeBy(PLAYER_ORIENTATION_WAIT_LIMIT_MILLIS - 1)
        runCurrent()
        assertTrue(gate.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = true))
        assertFalse(gate.placesPicture(ratioKnown = true, rotationPending = true))

        advanceTimeBy(1)
        runCurrent()
        assertFalse(gate.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = true))
        assertTrue(gate.placesPicture(ratioKnown = true, rotationPending = true))
    }
}
