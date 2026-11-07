package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoImageMemoryTest {

    @Test
    fun `命中刷新最近使用顺序`() {
        val memory = NoImageMemory(limit = 2)
        memory.remember("a")
        memory.remember("b")

        assertTrue(memory.contains("a"))
        memory.remember("c")

        assertTrue(memory.contains("a"))
        assertTrue(memory.contains("c"))
        assertFalse(memory.contains("b"))
    }

    @Test
    fun `移除后不再命中`() {
        val memory = NoImageMemory(limit = 2)
        memory.remember("a")

        memory.forget("a")

        assertFalse(memory.contains("a"))
    }

    @Test
    fun `默认有效期是十分钟`() {
        assertEquals(TTL_MILLIS, NoImageMemory.DEFAULT_TTL_MILLIS)
    }

    @Test
    fun `记忆到有效期前仍然命中`() {
        var now = 1_000L
        val memory = NoImageMemory(clock = { now })
        memory.remember("a")

        now += TTL_MILLIS - 1

        assertTrue(memory.contains("a"))
    }

    @Test
    fun `过期后不再命中`() {
        var now = 1_000L
        val memory = NoImageMemory(clock = { now })
        memory.remember("a")

        now += TTL_MILLIS

        assertFalse(memory.contains("a"))
    }

    @Test
    fun `过期后重新记住会重新计时`() {
        var now = 1_000L
        val memory = NoImageMemory(clock = { now })
        memory.remember("a")

        now += TTL_MILLIS
        assertFalse(memory.contains("a"))

        memory.remember("a")
        now += TTL_MILLIS - 1
        assertTrue(memory.contains("a"))
    }

    @Test
    fun `没有有效期的记忆不会过期`() {
        var now = 1_000L
        val memory = NoImageMemory(ttlMillis = null, clock = { now })
        memory.remember("a")

        now += 10 * TTL_MILLIS

        assertTrue(memory.contains("a"))
    }

    private companion object {
        /** 「确认无图」的记忆有效期：10 分钟。 */
        const val TTL_MILLIS = 600_000L
    }
}
