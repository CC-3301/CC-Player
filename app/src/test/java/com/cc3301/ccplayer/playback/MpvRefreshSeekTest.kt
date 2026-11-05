package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 刷新 seek 的序号归属：只有自己的 SEEK 与 PLAYBACK_RESTART 用来收尾。 */
class MpvRefreshSeekTest {

    @Test
    fun `刷新 seek 的 restart 只补报一次`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(5)

        assertTrue(refresh.onSeekConsumed(5))
        assertEquals(5, refresh.claimReceipt(5))
        assertNull(refresh.claimReceipt(5))
    }

    @Test
    fun `刷新在途时真实 seek 的 restart 不被吞`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(5)
        assertTrue(refresh.onSeekConsumed(5))

        // 真实 seek 的 SEEK 事件推进到新序号；它的 PLAYBACK_RESTART 不属于刷新，
        // 不认领就仍走普通 ready 与诊断路径。
        assertFalse(refresh.onSeekConsumed(6))
        assertNull(refresh.claimReceipt(6))
    }

    @Test
    fun `未等到 SEEK 的刷新序号超时标记为坟标`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(7)

        assertEquals(7, refresh.onTimeout(7))
        // 迟到的 SEEK 仍由这个坟标消费，不产生回执。
        assertTrue(refresh.onSeekConsumed(7))
        assertNull(refresh.claimReceipt(7))
    }

    @Test
    fun `清掉未消费的序号后不再认领回执`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(9)

        assertEquals(9, refresh.clear())
        assertNull(refresh.claimReceipt(9))
    }

    @Test
    fun `已消费的序号被清掉时不返回残留`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(9)
        assertTrue(refresh.onSeekConsumed(9))

        assertNull(refresh.clear())
    }

    @Test
    fun `已经见到 SEEK 的序号超时不丢弃`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(7)
        assertTrue(refresh.onSeekConsumed(7))

        assertNull(refresh.onTimeout(7))
        assertEquals(7, refresh.claimReceipt(7))
    }

    @Test
    fun `旧超时不清当前刷新的序号`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(7)
        refresh.begin(8)

        assertNull(refresh.onTimeout(7))
        assertTrue(refresh.onSeekConsumed(8))
        assertEquals(8, refresh.claimReceipt(8))
    }
}
