package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 黑底层只由控制层派生的画面判据与首次撤黑的方向等待决定：
 * 没有可显示的画面、本次播放还没拿到过视频比例、或还在等屏幕方向，就铺黑底。
 */
class PlayerBlackLayerTest {

    @Test
    fun `a visible picture with a known ratio keeps the black layer off`() {
        assertFalse(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = 0.5625,
                orientationSettled = true,
                orientationPending = false,
            ),
        )
    }

    @Test
    fun `no picture on the surface draws the black layer`() {
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = false,
                videoAspectRatio = 16.0 / 9.0,
                orientationSettled = true,
                orientationPending = false,
            ),
        )
    }

    @Test
    fun `an unknown ratio covers the picture before the first placement`() {
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = null,
                orientationSettled = false,
                orientationPending = false,
            ),
        )
    }

    @Test
    fun `the cover leaves with the first placement`() {
        val before = showPlayerBlackLayer(
            showsPicture = true,
            videoAspectRatio = null,
            orientationSettled = false,
            orientationPending = false,
        )
        val after = showPlayerBlackLayer(
            showsPicture = true,
            videoAspectRatio = 0.5625,
            orientationSettled = false,
            orientationPending = false,
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
                orientationSettled = true,
                orientationPending = false,
            ),
        )
    }

    @Test
    fun `the first placement keeps the cover while the screen is still turning`() {
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = 16.0 / 9.0,
                orientationSettled = false,
                orientationPending = true,
            ),
        )
    }
}
