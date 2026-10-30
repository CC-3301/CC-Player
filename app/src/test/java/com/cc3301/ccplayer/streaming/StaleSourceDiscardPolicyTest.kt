package com.cc3301.ccplayer.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 退到后台丢弃字节源的判定：条件、加载期间的推迟与补做。 */
class StaleSourceDiscardPolicyTest {

    private val policy = StaleSourceDiscardPolicy()

    @Test
    fun `后台播放开着且在播放时不丢`() {
        assertFalse(policy.onLeftForeground(backgroundPlaybackOff = false, paused = false, loading = false))
    }

    @Test
    fun `后台播放开着但当前暂停时也丢`() {
        assertTrue(policy.onLeftForeground(backgroundPlaybackOff = false, paused = true, loading = false))
    }

    @Test
    fun `后台播放关闭时不论暂停都丢`() {
        assertTrue(policy.onLeftForeground(backgroundPlaybackOff = true, paused = false, loading = false))
        assertTrue(policy.onLeftForeground(backgroundPlaybackOff = true, paused = true, loading = false))
    }

    @Test
    fun `加载中不丢，加载结束补做一次`() {
        assertFalse(policy.onLeftForeground(backgroundPlaybackOff = true, paused = false, loading = true))

        assertTrue(policy.onLoadingFinished())
        // 做完清标志，不会重复丢。
        assertFalse(policy.onLoadingFinished())
    }

    @Test
    fun `加载中没丢成的，回前台发起请求前补做一次`() {
        assertFalse(policy.onLeftForeground(backgroundPlaybackOff = true, paused = false, loading = true))

        assertTrue(policy.onReturnedToForeground())
        assertFalse(policy.onReturnedToForeground())
        // 回前台补做之后，加载结束不再重复。
        assertFalse(policy.onLoadingFinished())
    }

    @Test
    fun `不需要丢弃时加载中不记待丢弃`() {
        assertFalse(policy.onLeftForeground(backgroundPlaybackOff = false, paused = false, loading = true))
        assertFalse(policy.onLoadingFinished())
        assertFalse(policy.onReturnedToForeground())
    }
}
