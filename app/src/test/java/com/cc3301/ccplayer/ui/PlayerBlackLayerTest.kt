package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 黑底层只在需要黑底、或切集时不沿用屏幕上停住的旧帧时出现。 */
class PlayerBlackLayerTest {

    @Test
    fun `a switch keeps the old picture instead of a black layer`() {
        assertFalse(
            showPlayerBlackLayer(
                holdBlack = false,
                buffering = true,
                switching = true,
                keepsSurfaceFrame = true,
            ),
        )
    }

    @Test
    fun `a switch that does not keep the surface frame falls back to black`() {
        assertTrue(
            showPlayerBlackLayer(
                holdBlack = false,
                buffering = false,
                switching = true,
                keepsSurfaceFrame = false,
            ),
        )
    }

    @Test
    fun `the end hold stays black`() {
        assertTrue(
            showPlayerBlackLayer(
                holdBlack = true,
                buffering = false,
                switching = false,
                keepsSurfaceFrame = false,
            ),
        )
    }

    @Test
    fun `initial buffering keeps the black background`() {
        assertTrue(
            showPlayerBlackLayer(
                holdBlack = false,
                buffering = true,
                switching = false,
                keepsSurfaceFrame = true,
            ),
        )
    }
}
