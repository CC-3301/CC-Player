package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 黑底层只由控制层派生的画面判据决定：没有可显示的画面、或本次播放还没摆过视频比例，就铺黑底。 */
class PlayerBlackLayerTest {

    @Test
    fun `a visible picture with a known ratio keeps the black layer off`() {
        assertFalse(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = 0.5625,
                aspectRatioPlaced = true,
            ),
        )
    }

    @Test
    fun `no picture on the surface draws the black layer`() {
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = false,
                videoAspectRatio = 16.0 / 9.0,
                aspectRatioPlaced = true,
            ),
        )
    }

    @Test
    fun `an unknown ratio covers the picture before the first placement`() {
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = null,
                aspectRatioPlaced = false,
            ),
        )
    }

    @Test
    fun `the cover leaves with the first placement`() {
        val before = showPlayerBlackLayer(
            showsPicture = true,
            videoAspectRatio = null,
            aspectRatioPlaced = false,
        )
        val after = showPlayerBlackLayer(
            showsPicture = true,
            videoAspectRatio = 0.5625,
            aspectRatioPlaced = false,
        )

        assertTrue(before)
        assertFalse(after)
    }

    @Test
    fun `a placed ratio is not covered again`() {
        assertFalse(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = null,
                aspectRatioPlaced = true,
            ),
        )
    }
}
