package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class StableSystemBarInsetTest {

    @Test
    fun `初始未采样时顶部留白为 0`() {
        assertEquals(0.dp, StableSystemBarInset().value)
    }

    @Test
    fun `系统栏出现后按当前值留白`() {
        val inset = StableSystemBarInset()
        inset.observe(24.dp)
        assertEquals(24.dp, inset.value)
    }

    @Test
    fun `系统栏收起后不回缩`() {
        val inset = StableSystemBarInset()
        inset.observe(24.dp)
        inset.observe(0.dp)
        assertEquals(24.dp, inset.value)
    }

    @Test
    fun `更大的 inset 取代旧的最大值`() {
        val inset = StableSystemBarInset()
        inset.observe(24.dp)
        inset.observe(48.dp)
        inset.observe(32.dp)
        assertEquals(48.dp, inset.value)
    }
}
