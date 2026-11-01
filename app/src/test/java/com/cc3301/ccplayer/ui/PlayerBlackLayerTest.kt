package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 黑底层只由控制层派生的画面判据决定：没有可显示的画面就铺黑底。 */
class PlayerBlackLayerTest {

    @Test
    fun `a visible picture keeps the black layer off`() {
        assertFalse(showPlayerBlackLayer(showsPicture = true))
    }

    @Test
    fun `no picture on the surface draws the black layer`() {
        assertTrue(showPlayerBlackLayer(showsPicture = false))
    }
}
