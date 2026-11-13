package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPressFeedbackTest {

    private var clock = 0L
    private val feedback = PlayerPressFeedback(now = { clock })

    @Test
    fun `按下即显示 20 个百分点白块，按住一秒仍保持`() {
        feedback.press()

        assertEquals(PlayerPressPhase.Held, feedback.phase)
        assertEquals(PLAYER_PRESS_RIPPLE_ALPHA, feedback.alpha(), TOLERANCE)

        clock = 1_000

        assertEquals(PlayerPressPhase.Held, feedback.phase)
        assertEquals(PLAYER_PRESS_RIPPLE_ALPHA, feedback.alpha(), TOLERANCE)
    }

    @Test
    fun `松手后 120 毫秒淡出`() {
        feedback.press()
        clock = 500
        feedback.release()

        assertEquals(PlayerPressPhase.Fading, feedback.phase)
        assertEquals(PLAYER_PRESS_RIPPLE_ALPHA, feedback.alpha(), TOLERANCE)

        clock = 560
        assertEquals(PLAYER_PRESS_RIPPLE_ALPHA / 2f, feedback.alpha(), TOLERANCE)

        clock = 619
        assertTrue(feedback.alpha() > 0f)

        clock = 620
        assertEquals(0f, feedback.alpha(), TOLERANCE)
    }

    @Test
    fun `按下后才判定不可执行的反馈在 60 毫秒内撤掉`() {
        feedback.press()
        clock = 30
        feedback.revoke()

        assertEquals(PlayerPressPhase.Revoked, feedback.phase)
        assertEquals(PLAYER_PRESS_RIPPLE_ALPHA, feedback.alpha(), TOLERANCE)

        clock = 60
        assertEquals(PLAYER_PRESS_RIPPLE_ALPHA / 2f, feedback.alpha(), TOLERANCE)

        clock = 89
        assertTrue(feedback.alpha() > 0f)

        clock = 90
        assertEquals(0f, feedback.alpha(), TOLERANCE)
    }

    @Test
    fun `松手淡出途中被拦下时从当前不透明度继续撤掉`() {
        feedback.press()
        clock = 60
        feedback.release()

        clock = 90
        assertEquals(0.15f, feedback.alpha(), TOLERANCE)

        feedback.revoke()
        assertEquals(0.15f, feedback.alpha(), TOLERANCE)

        clock = 120
        assertEquals(0.075f, feedback.alpha(), TOLERANCE)

        clock = 150
        assertEquals(0f, feedback.alpha(), TOLERANCE)
    }

    @Test
    fun `未按下时松手或被拦下都不显示反馈`() {
        feedback.release()
        assertEquals(PlayerPressPhase.Idle, feedback.phase)
        assertEquals(0f, feedback.alpha(), TOLERANCE)

        feedback.revoke()
        assertEquals(PlayerPressPhase.Idle, feedback.phase)
        assertEquals(0f, feedback.alpha(), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
