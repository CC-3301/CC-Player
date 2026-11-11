package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewEntryStateTest {

    private var now = 1_000L

    private val states = PreviewEntryStates(limit = 2, clock = { now })

    @Test
    fun `失败累计已试次数并记下阶段`() {
        val state = states.stateFor("a")

        assertEquals(1, state.fail(PreviewFailureStage.Read))
        assertEquals(2, state.fail(PreviewFailureStage.Decode))

        assertEquals(2, state.attempts)
        assertEquals(PreviewEntryResult.Failed(PreviewFailureStage.Decode), state.result)
    }

    @Test
    fun `出图后失败计数与预取抑制一起清零`() {
        val state = states.stateFor("a")
        state.fail(PreviewFailureStage.Read)
        state.suppressPrefetch()

        state.succeed()

        assertEquals(0, state.attempts)
        assertEquals(PreviewEntryResult.Image, state.result)
        assertFalse(state.prefetchSuppressed)
    }

    @Test
    fun `新一轮可见订阅把已试次数从零计`() {
        val state = states.stateFor("a")
        state.fail(PreviewFailureStage.Read)
        state.fail(PreviewFailureStage.Read)

        state.resetAttempts()

        assertEquals(0, state.attempts)
        assertEquals("上次结果保留", PreviewEntryResult.Failed(PreviewFailureStage.Read), state.result)
    }

    @Test
    fun `状态记录上限与无图有效期取既定值`() {
        assertEquals(512, PreviewEntryStates.DEFAULT_LIMIT)
        assertEquals(600_000L, PreviewEntryState.NO_IMAGE_TTL_MILLIS)
    }

    @Test
    fun `无图记忆到有效期前仍然命中`() {
        states.rememberNoImage("a")

        now += PreviewEntryState.NO_IMAGE_TTL_MILLIS - 1

        assertTrue(states.isNoImageRemembered("a"))
    }

    @Test
    fun `无图记忆过期后不再命中`() {
        states.rememberNoImage("a")

        now += PreviewEntryState.NO_IMAGE_TTL_MILLIS

        assertFalse(states.isNoImageRemembered("a"))
    }

    @Test
    fun `过期后重新记住会重新计时`() {
        states.rememberNoImage("a")
        now += PreviewEntryState.NO_IMAGE_TTL_MILLIS
        assertFalse(states.isNoImageRemembered("a"))

        states.rememberNoImage("a")
        now += PreviewEntryState.NO_IMAGE_TTL_MILLIS - 1

        assertTrue(states.isNoImageRemembered("a"))
    }

    @Test
    fun `清掉无图记忆后不再命中`() {
        states.rememberNoImage("a")

        states.state("a")!!.forgetNoImage()

        assertFalse(states.isNoImageRemembered("a"))
    }

    @Test
    fun `预取抑制不随无图有效期过期`() {
        states.stateFor("a").suppressPrefetch()

        now += 10 * PreviewEntryState.NO_IMAGE_TTL_MILLIS

        assertTrue(states.state("a")!!.prefetchSuppressed)
    }

    @Test
    fun `记录满时逐条淘汰最久未用，不再整体清空`() {
        states.stateFor("a").fail(PreviewFailureStage.Read)
        states.stateFor("b").fail(PreviewFailureStage.Read)
        // 用一次 a：最近使用顺序变成 b、a。
        assertEquals(1, states.state("a")!!.attempts)

        states.stateFor("c").fail(PreviewFailureStage.Read)

        assertNull("最久未用的 b 被淘汰", states.state("b"))
        assertEquals("其余记录不清空", 1, states.state("a")!!.attempts)
        assertEquals(1, states.state("c")!!.attempts)
    }
}
