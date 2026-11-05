package com.cc3301.ccplayer.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbClientPoolTest {

    private var now = 1_000L
    private val clients = mutableListOf<RecordingSmbClient>()
    private val pool = SmbClientPool(
        factory = { config -> RecordingSmbClient(config).also { clients += it } },
        config = SmbJConnector.smb2OnlyConfig(),
        clock = { now },
        condemnedTimeoutMillis = 60_000,
    )

    @Test
    fun `作废不关闭在用连接，最后一个连接释放后才关`() {
        val slot = pool.acquire()

        pool.condemn(pool.generation)

        assertFalse("还有连接在用，不关闭", clients[0].closed)
        pool.release(slot)
        assertTrue("最后一个连接释放后关闭", clients[0].closed)
    }

    @Test
    fun `没有在用连接时作废后立刻关闭`() {
        pool.condemn(pool.generation)

        assertTrue(clients[0].closed)
    }

    @Test
    fun `作废后超过兜底时长强制关闭`() {
        val slot = pool.acquire()
        pool.condemn(pool.generation)

        now += 60_001
        pool.sweep()

        assertTrue("长期没人释放的连接被兜底关闭", clients[0].closed)
        pool.release(slot)
        assertTrue("再次归还不会出错", clients[0].closed)
    }

    @Test
    fun `作废换新实例并推进代数`() {
        assertEquals("初始代数为 0", 0L, pool.generation)

        pool.condemn(0L)

        assertEquals(1L, pool.generation)
        assertEquals(2, clients.size)
        assertTrue("旧实例已关闭", clients[0].closed)
        assertFalse("新实例可用", clients[1].closed)
    }

    @Test
    fun `代数已变时不再重复作废`() {
        pool.condemn(0L)

        val stale = pool.condemn(0L)

        assertNull("代数已变，不重复作废", stale)
        assertEquals(1L, pool.generation)
        assertEquals("只换过一次客户端", 2, clients.size)
    }
}
