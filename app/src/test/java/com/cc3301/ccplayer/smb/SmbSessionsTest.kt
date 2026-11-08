package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.hierynomus.protocol.transport.TransportException
import java.util.concurrent.TimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbSessionsTest {

    private val connector = FakeSmbConnector()

    private val target = SmbTarget(
        host = "nas",
        port = 445,
        share = "media",
        path = "电影.mkv",
        username = "cc",
        password = "secret",
    )

    private val payload = ByteArray(4096) { (it % 241).toByte() }

    @Test
    fun `目录浏览、封面与播放共用同一条会话`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)

        sessions.use(target) { connection -> connection.list("") }
        sessions.openRead(target).close()
        sessions.use(target) { connection -> connection.list("") }

        assertEquals("只握过一次手", 1, connector.shares.size)
        assertEquals("复用时各做一次存活确认", 2, connector.pings)
    }

    @Test
    fun `新建的会话不做存活确认，复用前才做`() = runTest {
        val sessions = SmbSessions(connector)

        sessions.probe(target)
        assertEquals(0, connector.pings)

        sessions.probe(target)
        assertEquals(1, connector.pings)
        assertEquals("存活确认一次往返的上限", 700L, connector.lastPingTimeout)
        assertEquals(1, connector.shares.size)
    }

    @Test
    fun `存活确认失败时作废并按新会话重建一次`() = runTest {
        val sessions = SmbSessions(connector)
        sessions.probe(target)
        connector.pingFailure = MediaSourceException(MediaFailure.Unreachable, "会话已被服务器回收")

        sessions.probe(target)

        assertEquals("作废后重连一次", 2, connector.shares.size)
        assertEquals(listOf("media"), connector.closedShares)
    }

    @Test
    fun `传输类读失败只作废会话，把原失败抛给调用方`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        val broken = MediaSourceException(MediaFailure.Unreachable, "传输中断")
        connector.readFailure = broken

        val failure = runCatching { source.readAt(0, ByteArray(16), 0, 16) }.exceptionOrNull()

        assertEquals("原失败原样抛出", broken, failure)
        assertEquals("不再有第二次握手", 1, connector.shares.size)
        assertEquals("只作废这一条会话", listOf("media"), connector.closedShares)
        assertEquals("只打开过一个句柄", 1, connector.allHandles.size)
    }

    @Test
    fun `作废后的下一次取用重新建连`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        connector.readFailure = MediaSourceException(MediaFailure.Unreachable, "传输中断")
        runCatching { source.readAt(0, ByteArray(16), 0, 16) }

        connector.readFailure = null
        val reopened = sessions.openRead(target)
        val buffer = ByteArray(16)
        val read = reopened.readAt(0, buffer, 0, buffer.size)

        assertArrayEquals("重取由下一次请求完成", payload.copyOfRange(0, 16), buffer)
        assertEquals(16, read)
        assertEquals("第二次握手只发生在这里", 2, connector.shares.size)
        reopened.close()
    }

    @Test
    fun `读停滞不在这里重连`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        // smbj 把单次读的等待超时包进传输异常，根因是 java.util.concurrent.TimeoutException。
        val stalled = TransportException(TimeoutException("读超时"))
        connector.readFailure = stalled

        val failure = runCatching { source.readAt(0, ByteArray(16), 0, 16) }.exceptionOrNull()

        assertEquals(stalled, failure)
        assertEquals("停滞类由回环层打陈旧标记后重取该段", 1, connector.shares.size)
    }

    @Test
    fun `读取的业务失败不作废会话`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        connector.readFailure = MediaSourceException(MediaFailure.NotFound, "文件已被删除")

        runCatching { source.readAt(0, ByteArray(16), 0, 16) }

        assertEquals("业务失败不重连", 1, connector.shares.size)
    }

    @Test
    fun `打开路径不存在时不作废会话`() = runTest {
        connector.addFile("media", "还在.mkv", payload)
        val sessions = SmbSessions(connector)

        val missing = runCatching { sessions.openRead(target) }.exceptionOrNull()
        sessions.openRead(target.copy(path = "还在.mkv")).close()

        assertEquals(MediaFailure.NotFound, (missing as MediaSourceException).failure)
        assertEquals("路径不存在不重连", 1, connector.shares.size)
    }

    @Test
    fun `列表遇停滞也作废会话后重连重发一次`() = runTest {
        connector.listFailure = TransportException(TimeoutException("读超时"))
        val sessions = SmbSessions(connector)

        runCatching { sessions.use(target) { connection -> connection.list("") } }

        assertEquals("列表没有上层重取，这里必须补一次", 2, connector.shares.size)
        assertEquals("两条都作废", 2, connector.closedShares.size)
    }

    @Test
    fun `建立失败时打开句柄只建一次会话`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        connector.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")
        val sessions = SmbSessions(connector)

        val failure = runCatching { sessions.openRead(target) }.exceptionOrNull()

        assertTrue("建立失败原样抛出", failure is MediaSourceException)
        assertEquals("建立不在重发范围", 1, connector.connectAttempts)
    }

    @Test
    fun `先关读源再收到迟到的停滞失败，会话仍被关`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector, scope = this, idleMillis = 1_000)
        val source = sessions.openRead(target)
        // 这次读会失败，但句柄与引用已经先一步交还。
        connector.readFailure = TransportException(TimeoutException("读超时"))
        source.close()

        runCatching { source.readAt(0, ByteArray(16), 0, 16) }

        // 迟到的作废不再碰会话表：连接交给闲置回收，不在这里当场关。
        assertTrue("迟到的作废不摘会话", connector.closedShares.isEmpty())
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("media"), connector.closedShares)
    }

    @Test
    fun `会话建立失败不重发`() = runTest {
        connector.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")
        val sessions = SmbSessions(connector)

        runCatching { sessions.use(target) { connection -> connection.list("") } }

        assertEquals("建立只尝试一次", 1, connector.connectAttempts)
    }

    @Test
    fun `重发再次失败同样作废会话`() = runTest {
        connector.listFailure = MediaSourceException(MediaFailure.Unreachable, "断网")
        val sessions = SmbSessions(connector)

        runCatching { sessions.use(target) { connection -> connection.list("") } }

        assertEquals("两次尝试各建一条会话", 2, connector.shares.size)
        assertEquals("两条都作废", 2, connector.closedShares.size)
    }

    @Test
    fun `请求被取消时不摘掉健康会话`() = runTest {
        val sessions = SmbSessions(connector)
        sessions.probe(target)

        val request = launch { sessions.use(target) { awaitCancellation() } }
        runCurrent()
        request.cancelAndJoin()

        assertTrue("取消不是会话失效", connector.closedShares.isEmpty())
        sessions.probe(target)
        assertEquals(1, connector.shares.size)
    }

    @Test
    fun `会话建立超出预算时报失败`() = runTest {
        connector.connectDelayMillis = 6_000
        val sessions = SmbSessions(connector, scope = this)

        val failure = runCatching { sessions.probe(target) }.exceptionOrNull()

        assertEquals(MediaFailure.Unreachable, (failure as MediaSourceException).failure)
        assertEquals("预算到点就放弃，不再重试一次建立", 1, connector.connectAttempts)
    }

    @Test
    fun `并发建连最终收敛到一条会话`() = runBlocking {
        val sessions = SmbSessions(connector)
        val arrived = CountDownLatch(2)
        val gate = CountDownLatch(1)
        connector.duringConnect = {
            arrived.countDown()
            gate.await()
        }
        val first = thread { runBlocking { sessions.probe(target) } }
        val second = thread { runBlocking { sessions.probe(target) } }
        assertTrue("两条建连都开始了", arrived.await(5, TimeUnit.SECONDS))
        gate.countDown()
        first.join(5_000)
        second.join(5_000)
        connector.duringConnect = null

        assertEquals("先登记的留下", 1, connector.closedShares.size)
        sessions.probe(target)
        assertEquals("之后只复用同一条会话", 2, connector.shares.size)
    }

    @Test
    fun `闲置且没有引用时关闭，下次取用懒重建`() = runTest {
        val sessions = SmbSessions(connector, scope = this, idleMillis = 1_000)
        sessions.probe(target)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("media"), connector.closedShares)

        sessions.probe(target)
        assertEquals("懒重建", 2, connector.shares.size)
    }

    @Test
    fun `还有读句柄在用时闲置计时不关会话`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector, scope = this, idleMillis = 1_000)
        val source = sessions.openRead(target)

        advanceTimeBy(2_000)
        runCurrent()
        assertTrue("句柄没关就不许回收", connector.closedShares.isEmpty())

        source.close()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("media"), connector.closedShares)
    }

    @Test
    fun `会话表超过上限时关掉最久未用的`() = runTest {
        val sessions = SmbSessions(connector, maxSessions = 1)

        sessions.probe(target)
        sessions.probe(target.copy(share = "剧集"))

        assertEquals(listOf("media"), connector.closedShares)
    }
}
