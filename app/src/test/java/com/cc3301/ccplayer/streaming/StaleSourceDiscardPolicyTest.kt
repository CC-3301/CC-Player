package com.cc3301.ccplayer.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 退到后台丢弃字节源的判定：条件、加载期间的推迟与补做。 */
class StaleSourceDiscardPolicyTest {

    private val policy = StaleSourceDiscardPolicy()
    private var discarded = 0

    private val discard: () -> Boolean = {
        discarded++
        true
    }

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

        assertTrue(policy.onLoadingFinished(inBackground = true, discard = discard))
        assertEquals(1, discarded)
        // 做完清标志，不会重复丢。
        assertFalse(policy.onLoadingFinished(inBackground = true, discard = discard))
        assertEquals(1, discarded)
    }

    @Test
    fun `加载结束时已回到前台，留给回前台补做一次`() {
        assertFalse(policy.onLeftForeground(backgroundPlaybackOff = true, paused = false, loading = true))

        // 加载结束时人已经回到前台：不在这里丢，标志留着等回前台补做。
        assertFalse(policy.onLoadingFinished(inBackground = false, discard = discard))
        assertEquals(0, discarded)

        assertTrue(policy.onReturnedToForeground(discard))
        assertEquals(1, discarded)
        assertFalse(policy.onReturnedToForeground(discard))
        assertEquals(1, discarded)
    }

    @Test
    fun `补做没执行成时标志留着，下一次还会补做`() {
        assertFalse(policy.onLeftForeground(backgroundPlaybackOff = true, paused = false, loading = true))

        // 流还没建立，这次没真的丢：标志不能被吞掉。
        assertFalse(policy.onReturnedToForeground { false })
        assertEquals(0, discarded)

        assertTrue(policy.onReturnedToForeground(discard))
        assertEquals(1, discarded)
    }

    @Test
    fun `不需要丢弃时加载中不记待丢弃`() {
        assertFalse(policy.onLeftForeground(backgroundPlaybackOff = false, paused = false, loading = true))
        assertFalse(policy.onLoadingFinished(inBackground = true, discard = discard))
        assertFalse(policy.onReturnedToForeground(discard))
        assertEquals(0, discarded)
    }
}
