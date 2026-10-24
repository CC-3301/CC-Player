package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 黑底层只在片尾、首次缓冲或切集已明确无帧时出现。 */
class PlayerBlackLayerTest {

    @Test
    fun `a switch while the freeze frame is still being grabbed keeps the old picture`() {
        assertFalse(
            showPlayerBlackLayer(
                holdBlack = false,
                buffering = true,
                switching = true,
                hasFreezeFrame = false,
                freezeFramePending = true,
            ),
        )
    }

    @Test
    fun `a switch with no frame after the grab falls back to black`() {
        assertTrue(
            showPlayerBlackLayer(
                holdBlack = false,
                buffering = false,
                switching = true,
                hasFreezeFrame = false,
                freezeFramePending = false,
            ),
        )
    }

    @Test
    fun `an available freeze frame covers the surface`() {
        assertFalse(
            showPlayerBlackLayer(
                holdBlack = false,
                buffering = false,
                switching = true,
                hasFreezeFrame = true,
                freezeFramePending = false,
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
                hasFreezeFrame = false,
                freezeFramePending = false,
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
                hasFreezeFrame = false,
                freezeFramePending = false,
            ),
        )
    }
}
