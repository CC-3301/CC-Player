package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 刷新 seek 的序号归属：只有自己的 SEEK 与 PLAYBACK_RESTART 用来收尾，取消与超时都转坟标。 */
class MpvRefreshSeekTest {

    @Test
    fun `刷新 seek 的 restart 只补报一次`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(5, issuedAtMillis = 0)

        assertTrue(refresh.onSeekConsumed(5))
        assertEquals(5, refresh.claimReceipt(5))
        assertNull(refresh.claimReceipt(5))
    }

    @Test
    fun `刷新在途时真实 seek 的 restart 不被吞`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(5, issuedAtMillis = 0)
        assertTrue(refresh.onSeekConsumed(5))

        // 真实 seek 的 SEEK 事件推进到新序号；它的 PLAYBACK_RESTART 不属于刷新，
        // 不认领就仍走普通 ready 与诊断路径。
        assertFalse(refresh.onSeekConsumed(6))
        assertNull(refresh.claimReceipt(6))
    }

    @Test
    fun `未等到 SEEK 的刷新序号超时登记为坟标`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(7, issuedAtMillis = 1_000)

        // 坟标保留队列位，回收时间点取下发时刻加等待窗口。
        assertEquals(RefreshTombstone(7, 4_000), refresh.onTimeout(7))
        // 迟到的 SEEK 仍由这个坟标消费，不产生回执。
        assertTrue(refresh.onSeekConsumed(7))
        assertNull(refresh.claimReceipt(7))
    }

    @Test
    fun `已经见到 SEEK 的刷新序号超时不再登记坟标`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(7, issuedAtMillis = 0)
        assertTrue(refresh.onSeekConsumed(7))

        assertNull(refresh.onTimeout(7))
        assertEquals(7, refresh.claimReceipt(7))
    }

    @Test
    fun `取消把未消费的刷新序号登记为坟标`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(9, issuedAtMillis = 1_000)

        // 翻转/分离时命令可能已经下发：序号转坟标保留队列位，不直接丢号。
        assertEquals(RefreshTombstone(9, 4_000), refresh.cancel())
        assertNull(refresh.cancel())
        assertNull(refresh.claimReceipt(9))
    }

    @Test
    fun `已经见到 SEEK 的刷新序号取消时只清归属`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(10, issuedAtMillis = 0)
        assertTrue(refresh.onSeekConsumed(10))

        // 队列位已被这条 SEEK 消费，取消没有坟标可登记。
        assertNull(refresh.cancel())
        assertNull(refresh.claimReceipt(10))
    }

    @Test
    fun `已经登记坟标的刷新序号取消时不重复登记`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(11, issuedAtMillis = 0)
        assertEquals(RefreshTombstone(11, 3_000), refresh.onTimeout(11))

        assertNull(refresh.cancel())
        assertNull(refresh.claimReceipt(11))
    }

    @Test
    fun `作废直接返回要撤掉的预留序号`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(12, issuedAtMillis = 0)

        assertEquals(12, refresh.discard())
        assertNull(refresh.discard())
        assertNull(refresh.claimReceipt(12))
    }

    @Test
    fun `旧超时不清当前刷新的序号`() {
        val refresh = MpvRefreshSeek()
        refresh.begin(7, issuedAtMillis = 0)
        refresh.begin(8, issuedAtMillis = 0)

        assertNull(refresh.onTimeout(7))
        assertTrue(refresh.onSeekConsumed(8))
        assertEquals(8, refresh.claimReceipt(8))
    }
}
