package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 缓冲提示：打开与恢复原片显示转圈和文字，切集（含片尾自动切集）不显示。 */
class PlayerBufferingIndicatorTest {

    @Test
    fun `打开缓冲显示转圈与文字`() {
        val hint = bufferingHint(
            kind = bufferingLoadKind(switching = false, restoring = false, initialLoading = true),
            buffering = true,
            showsPicture = false,
            quietBlack = false,
        )

        assertTrue(hint.visible)
        assertTrue(hint.showText)
    }

    @Test
    fun `恢复原片缓冲显示转圈与文字`() {
        val hint = bufferingHint(
            kind = bufferingLoadKind(switching = true, restoring = true, initialLoading = false),
            buffering = true,
            showsPicture = false,
            quietBlack = false,
        )

        assertTrue(hint.visible)
        assertTrue(hint.showText)
    }

    @Test
    fun `切集缓冲不显示提示`() {
        val hint = bufferingHint(
            kind = bufferingLoadKind(switching = true, restoring = false, initialLoading = false),
            buffering = true,
            showsPicture = true,
            quietBlack = false,
        )

        assertFalse(hint.visible)
        assertFalse(hint.showText)
    }

    @Test
    fun `静默黑屏的普通缓冲不显示提示`() {
        val hint = bufferingHint(
            kind = BufferingLoadKind.Idle,
            buffering = true,
            showsPicture = false,
            quietBlack = true,
        )

        assertFalse(hint.visible)
    }

    @Test
    fun `有画面时普通缓冲显示提示`() {
        val hint = bufferingHint(
            kind = BufferingLoadKind.Idle,
            buffering = true,
            showsPicture = true,
            quietBlack = true,
        )

        assertTrue(hint.visible)
        assertTrue(hint.showText)
    }

    @Test
    fun `没有缓冲时不显示提示`() {
        val hint = bufferingHint(
            kind = BufferingLoadKind.Open,
            buffering = false,
            showsPicture = true,
            quietBlack = false,
        )

        assertFalse(hint.visible)
    }
}
