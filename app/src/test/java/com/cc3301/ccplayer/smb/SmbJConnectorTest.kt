package com.cc3301.ccplayer.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbJConnectorTest {

    @Test
    fun `只协商 SMB2 与 SMB3 方言`() {
        val config = SmbJConnector.smb2OnlyConfig()

        assertEquals(
            setOf(
                SMB2Dialect.SMB_2_0_2,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_1_1,
            ),
            config.supportedDialects,
        )
        assertFalse(SMB2Dialect.UNKNOWN in config.supportedDialects)
    }

    @Test
    fun `关掉 SMB1 多协议协商`() {
        assertFalse(SmbJConnector.smb2OnlyConfig().isUseMultiProtocolNegotiate)
    }

    @Test
    fun `重建后建连走新客户端`() = runTest {
        val clients = CopyOnWriteArrayList<RecordingSmbClient>()
        val connector = SmbJConnector(clientFactory = { config -> RecordingSmbClient(config).also { clients += it } })

        runCatching { connector.connect("nas", 445, "media", "cc", "secret") }
        assertEquals("重建前用第一个客户端", 1, clients[0].connects.size)

        connector.reset()
        runCatching { connector.connect("nas", 445, "media", "cc", "secret") }

        assertEquals(2, clients.size)
        assertEquals("重建后由新客户端建连", 1, clients[1].connects.size)
        assertTrue("旧客户端已关闭", clients[0].closed)
        assertFalse("新客户端仍可用", clients[1].closed)
    }

    @Test
    fun `并发重建不关掉刚换上的新客户端`() {
        val clients = CopyOnWriteArrayList<RecordingSmbClient>()
        val creating = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connector = SmbJConnector(
            clientFactory = { config ->
                if (creating.getAndIncrement() == 1) {
                    // 第一次重建卡在建新实例之前，制造「第二次重建同时到达」的窗口。
                    entered.countDown()
                    release.await()
                }
                RecordingSmbClient(config).also { clients += it }
            },
        )

        val first = Thread { connector.reset() }
        first.start()
        entered.await()

        val second = Thread { connector.reset() }
        second.start()
        // 第二个调用先取客户端快照再等锁：等它阻塞在锁上，快照必然还是旧实例。
        awaitBlocked(second)
        release.countDown()
        first.join()
        second.join()

        assertEquals("两次重建只换掉旧客户端", 2, clients.size)
        assertTrue("旧客户端被关闭", clients[0].closed)
        assertFalse("刚换上的新客户端不得被关闭", clients[1].closed)
    }

    /** 等到线程阻塞在锁上，最多等 5 秒。 */
    private fun awaitBlocked(thread: Thread) {
        val deadline = System.currentTimeMillis() + 5_000
        while (thread.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.sleep(1)
    }

    /** 记录被关闭与建连次数，不真的建连。 */
    private class RecordingSmbClient(config: SmbConfig) : SMBClient(config) {
        var closed = false
        val connects = CopyOnWriteArrayList<String>()

        override fun connect(host: String, port: Int): Connection {
            connects += host
            throw IOException("测试用客户端不可用")
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
