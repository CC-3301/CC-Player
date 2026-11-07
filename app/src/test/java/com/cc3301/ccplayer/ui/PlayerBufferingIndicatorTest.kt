package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 缓冲提示：打开与恢复原片显示转圈和文字，切集（含片尾自动切集）不显示。 */
class PlayerBufferingIndicatorTest {

    @Test
    fun `打开缓冲显示转圈与文字`() {
        assertTrue(
            bufferingHint(
                kind = bufferingLoadKind(switching = false, restoring = false, initialLoading = true),
                buffering = true,
                showsPicture = false,
                quietBlack = false,
            ),
        )
    }

    @Test
    fun `打开缓冲在静默黑屏下仍显示`() {
        // 打开另一个目标会带静默黑屏标记；缓冲提示不能因此被压掉。
        assertTrue(
            bufferingHint(
                kind = BufferingLoadKind.Open,
                buffering = true,
                showsPicture = false,
                quietBlack = true,
            ),
        )
    }

    @Test
    fun `恢复原片缓冲显示转圈与文字`() {
        assertTrue(
            bufferingHint(
                kind = bufferingLoadKind(switching = true, restoring = true, initialLoading = false),
                buffering = true,
                showsPicture = false,
                quietBlack = false,
            ),
        )
    }

    @Test
    fun `切集缓冲不显示提示`() {
        assertFalse(
            bufferingHint(
                kind = bufferingLoadKind(switching = true, restoring = false, initialLoading = false),
                buffering = true,
                showsPicture = true,
                quietBlack = false,
            ),
        )
    }

    @Test
    fun `静默黑屏的普通缓冲不显示提示`() {
        assertFalse(
            bufferingHint(
                kind = BufferingLoadKind.Idle,
                buffering = true,
                showsPicture = false,
                quietBlack = true,
            ),
        )
    }

    @Test
    fun `有画面时普通缓冲显示提示`() {
        assertTrue(
            bufferingHint(
                kind = BufferingLoadKind.Idle,
                buffering = true,
                showsPicture = true,
                quietBlack = true,
            ),
        )
    }

    @Test
    fun `没有缓冲时不显示提示`() {
        assertFalse(
            bufferingHint(
                kind = BufferingLoadKind.Open,
                buffering = false,
                showsPicture = true,
                quietBlack = false,
            ),
        )
    }
}
