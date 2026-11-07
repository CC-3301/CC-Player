package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import java.net.SocketTimeoutException
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
    fun `传输类读失败时作废会话、重连并重发一次`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        connector.readFailure = MediaSourceException(MediaFailure.Unreachable, "传输中断")
        connector.readFailureTimes = 1

        val buffer = ByteArray(16)
        val read = source.readAt(0, buffer, 0, buffer.size)

        assertEquals(16, read)
        assertArrayEquals(payload.copyOfRange(0, 16), buffer)
        assertEquals("作废后重连一次", 2, connector.shares.size)
    }

    @Test
    fun `换会话先关旧句柄再开新句柄`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        connector.readFailure = MediaSourceException(MediaFailure.Unreachable, "传输中断")
        connector.readFailureTimes = 1

        source.readAt(0, ByteArray(16), 0, 16)

        assertEquals(listOf("open", "close", "open"), connector.openClose)
    }

    @Test
    fun `读停滞不在这里重连`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        connector.readFailure = SocketTimeoutException("读超时")

        val failure = runCatching { source.readAt(0, ByteArray(16), 0, 16) }.exceptionOrNull()

        assertTrue(failure is SocketTimeoutException)
        assertEquals("停滞类交给回环层重发", 1, connector.shares.size)
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
    }

    @Test
    fun `重连期间关闭读源不等网络`() = runBlocking {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        connector.readFailure = MediaSourceException(MediaFailure.Unreachable, "传输中断")
        connector.readFailureTimes = 1
        val connecting = CountDownLatch(1)
        val gate = CountDownLatch(1)
        connector.duringConnect = {
            connecting.countDown()
            gate.await()
        }

        val reading = thread { runCatching { source.readAt(0, ByteArray(16), 0, 16) } }
        assertTrue("重连已开始", connecting.await(5, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        source.close()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        gate.countDown()
        reading.join(5_000)

        assertTrue("关闭不等重连，实际 ${elapsedMillis}ms", elapsedMillis < 1_000)
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
