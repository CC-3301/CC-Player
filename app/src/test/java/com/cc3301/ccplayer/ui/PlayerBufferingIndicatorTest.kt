package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 缓冲提示显示判据：只有后台切集与播放态回前台撤帧两类黑屏不显示。 */
class PlayerBufferingIndicatorTest {

    @Test
    fun `静默黑屏不显示提示`() {
        assertFalse(
            showsBufferingIndicator(
                buffering = true,
                showsPicture = false,
                firstBuffering = false,
                quietBlack = true,
            ),
        )
    }

    @Test
    fun `首次缓冲在静默黑屏下仍显示提示`() {
        assertTrue(
            showsBufferingIndicator(
                buffering = true,
                showsPicture = false,
                firstBuffering = true,
                quietBlack = true,
            ),
        )
    }

    @Test
    fun `有画面时显示提示`() {
        assertTrue(
            showsBufferingIndicator(
                buffering = true,
                showsPicture = true,
                firstBuffering = false,
                quietBlack = false,
            ),
        )
    }

    @Test
    fun `普通黑屏维持原提示规则`() {
        assertTrue(
            showsBufferingIndicator(
                buffering = true,
                showsPicture = false,
                firstBuffering = false,
                quietBlack = false,
            ),
        )
    }

    @Test
    fun `没有缓冲时不显示提示`() {
        assertFalse(
            showsBufferingIndicator(
                buffering = false,
                showsPicture = true,
                firstBuffering = true,
                quietBlack = false,
            ),
        )
    }
}
