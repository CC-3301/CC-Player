package com.cc3301.ccplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 落定 seek 的到达判据：越过目标且连续两个样本，单个样本（引擎的乐观写回）不算到达。 */
class SeekArrivalTest {

    @Test
    fun `正向跳转要连续两个越过目标的样本`() {
        val arrival = SeekArrival(targetMillis = 120_000, forward = true)

        // 迟到的旧位置还在起点一侧。
        assertFalse(arrival.onPosition(80_000))
        // 引擎下发 seek 时把位置乐观写成目标：单个样本不算到达。
        assertFalse(arrival.onPosition(120_000))
        assertTrue(arrival.onPosition(120_010))
    }

    @Test
    fun `退回目标一侧会清零`() {
        val arrival = SeekArrival(targetMillis = 120_000, forward = true)

        assertFalse(arrival.onPosition(120_000))
        // 旧位置样本插进来：之前的计数作废。
        assertFalse(arrival.onPosition(80_000))
        assertFalse(arrival.onPosition(120_000))
        assertTrue(arrival.onPosition(120_000))
    }

    @Test
    fun `反向跳转按目标另一侧判定`() {
        // 1:40 拖到 1:20：位置降到目标及以下才算越过。
        val arrival = SeekArrival(targetMillis = 80_000, forward = false)

        assertFalse(arrival.onPosition(100_000))
        assertFalse(arrival.onPosition(80_000))
        assertTrue(arrival.onPosition(79_900))
    }

    @Test
    fun `反向起点与目标之间不判到达`() {
        // 起点 1:30、目标 0:45：位置还在起点与目标之间时不能撤保持值。
        val arrival = SeekArrival(targetMillis = 45_000, forward = false)

        assertFalse(arrival.onPosition(80_000))
        assertFalse(arrival.onPosition(44_000))
        assertTrue(arrival.onPosition(44_000))
    }

    @Test
    fun `目标就是起点时同样要连续两个样本`() {
        val arrival = SeekArrival(targetMillis = 45_000, forward = true)

        assertFalse(arrival.onPosition(45_000))
        assertTrue(arrival.onPosition(45_000))
    }
}
