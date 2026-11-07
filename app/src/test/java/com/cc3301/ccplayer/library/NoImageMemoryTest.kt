package com.cc3301.ccplayer.library

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
    fun `记忆到有效期前仍然命中`() {
        var now = 1_000L
        val memory = NoImageMemory(clock = { now })
        memory.remember("a")

        now += NoImageMemory.DEFAULT_TTL_MILLIS - 1

        assertTrue(memory.contains("a"))
    }

    @Test
    fun `过期后不再命中`() {
        var now = 1_000L
        val memory = NoImageMemory(clock = { now })
        memory.remember("a")

        now += NoImageMemory.DEFAULT_TTL_MILLIS

        assertFalse(memory.contains("a"))
    }

    @Test
    fun `过期后重新记住会重新计时`() {
        var now = 1_000L
        val memory = NoImageMemory(clock = { now })
        memory.remember("a")

        now += NoImageMemory.DEFAULT_TTL_MILLIS
        assertFalse(memory.contains("a"))

        memory.remember("a")
        now += NoImageMemory.DEFAULT_TTL_MILLIS - 1
        assertTrue(memory.contains("a"))
    }
}
