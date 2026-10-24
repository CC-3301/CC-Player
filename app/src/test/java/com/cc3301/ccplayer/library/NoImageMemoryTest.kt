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
}
