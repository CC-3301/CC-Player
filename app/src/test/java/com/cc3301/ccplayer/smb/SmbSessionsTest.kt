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

    @Test
    fun `同一服务器的不同共享共用一条传输`() = runTest {
        val sessions = SmbSessions(connector)

        sessions.probe(target)
        sessions.probe(target.copy(share = "剧集"))

        assertEquals("两条会话", 2, connector.shares.size)
        assertEquals("一条传输", 1, connector.transports.size)
    }

    @Test
    fun `会话失效只重建会话，传输沿用`() = runTest {
        val sessions = SmbSessions(connector)
        sessions.probe(target)
        connector.pingFailure = sessionLostFailure()

        sessions.probe(target)

        assertEquals("会话重建一次", 2, connector.shares.size)
        assertEquals("传输继续用", 1, connector.transports.size)
        assertTrue("传输没被换掉", connector.closedTransports.isEmpty())
    }

    @Test
    fun `本地传输失效换掉故障服务器端口的传输`() = runTest {
        connector.listFailure = TransportException(TimeoutException("读超时"))
        val sessions = SmbSessions(connector)

        runCatching { sessions.use(target) { connection -> connection.list("") } }

        assertEquals("重发建在新传输上", 2, connector.transports.size)
        assertTrue("旧传输关掉", connector.transports[0].closed)
    }

    @Test
    fun `换传输只影响故障的服务器端口`() = runTest {
        val other = target.copy(host = "别的服务器", share = "剧集")
        connector.addDirectory("media", "", fileEntry("电影.mkv"))
        connector.addDirectory("剧集", "", fileEntry("第一集.mkv"))
        val sessions = SmbSessions(connector)
        sessions.probe(target)
        sessions.probe(other)
        connector.listFailure = TransportException(TimeoutException("读超时"))
        runCatching { sessions.use(target) { connection -> connection.list("") } }

        connector.listFailure = null
        sessions.probe(other)

        assertTrue("故障服务器的旧传输关掉", connector.transports[0].closed)
        assertEquals("另一台服务器的传输还在", false, connector.transports[1].closed)
    }

    @Test
    fun `本地半死传输不会被重建的会话继续复用`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val source = sessions.openRead(target)
        connector.readFailure = TransportException(TimeoutException("读超时"))
        runCatching { source.readAt(0, ByteArray(16), 0, 16) }

        connector.readFailure = null
        sessions.openRead(target).close()

        assertEquals("下一次取用建在新传输上", 2, connector.transports.size)
        assertEquals("旧传输作废", listOf("nas:445"), connector.closedTransports)
    }

    @Test
    fun `播放持有读取租约时浏览操作不关也不换共享传输`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val playback = sessions.openPlaybackRead(target)
        connector.listFailure = TransportException(TimeoutException("读超时"))

        val failure = runCatching { sessions.use(target) { connection -> connection.list("") } }.exceptionOrNull()

        assertTrue("失败原样交给调用方", failure is TransportException)
        assertTrue("不换传输", connector.transports.size == 1)
        assertTrue("不关传输", connector.closedTransports.isEmpty())
        assertEquals("不重发", 1, connector.shares.size)

        connector.listFailure = null
        playback.close()
    }

    @Test
    fun `没有播放读取租约时浏览操作可以重建传输`() = runTest {
        connector.addDirectory("media", "", fileEntry("电影.mkv"))
        val sessions = SmbSessions(connector)
        sessions.probe(target)
        connector.listFailure = TransportException(TimeoutException("读超时"))

        runCatching { sessions.use(target) { connection -> connection.list("") } }

        assertEquals("换了一条传输", 2, connector.transports.size)
        assertTrue("旧传输关掉", connector.transports[0].closed)
    }

    @Test
    fun `播放自己的故障流程可以换掉这条传输`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val playback = sessions.openPlaybackRead(target)
        connector.readFailure = TransportException(TimeoutException("读超时"))

        runCatching { playback.readAt(0, ByteArray(16), 0, 16) }

        assertEquals("传输作废并关闭", listOf("nas:445"), connector.closedTransports)

        connector.readFailure = null
        sessions.openPlaybackRead(target).close()

        assertEquals("下一次播放读建新传输", 2, connector.transports.size)
    }

    @Test
    fun `播放持有读取租约时封面读取失败不关也不换共享传输`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val cover = sessions.openRead(target)
        val playback = sessions.openPlaybackRead(target)
        connector.readFailure = TransportException(TimeoutException("读超时"))

        runCatching { cover.readAt(0, ByteArray(16), 0, 16) }

        assertEquals("不换传输", 1, connector.transports.size)
        assertTrue("不关传输", connector.closedTransports.isEmpty())

        playback.close()
    }

    @Test
    fun `播放持有读取租约时探测遇传输失效不换共享传输`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        val sessions = SmbSessions(connector)
        val playback = sessions.openPlaybackRead(target)
        connector.pingFailure = TransportException(TimeoutException("读超时"))

        val failure = runCatching { sessions.probe(target) }.exceptionOrNull()

        assertTrue("失败交给调用方", failure is TransportException)
        assertEquals("不换传输", 1, connector.transports.size)
        assertTrue("不关传输", connector.closedTransports.isEmpty())

        playback.close()
    }

    @Test
    fun `认证失败不作废也不重建`() = runTest {
        connector.addDirectory("media", "", fileEntry("电影.mkv"))
        val sessions = SmbSessions(connector)
        sessions.probe(target)
        val denied = MediaSourceException(MediaFailure.Authentication, "凭据被拒")
        connector.listFailure = denied

        val failure = runCatching { sessions.use(target) { connection -> connection.list("") } }.exceptionOrNull()

        assertEquals(denied, failure)
        assertEquals("不重发", 1, connector.shares.size)
        assertTrue("不换传输", connector.closedTransports.isEmpty())
        assertEquals("不作废会话", emptyList<String>(), connector.closedShares)
    }

    @Test
    fun `闲置回收只关本会话，传输留给仍在用的会话`() = runTest {
        connector.addFile("media", "电影.mkv", payload)
        connector.addFile("剧集", "第一集.mkv", payload)
        val sessions = SmbSessions(connector, scope = this, idleMillis = 1_000)
        val held = sessions.openRead(target.copy(share = "剧集", path = "第一集.mkv"))
        sessions.probe(target)

        advanceTimeBy(1_001)
        runCurrent()

        assertEquals("电影会话闲置关闭", listOf("media"), connector.closedShares)
        assertTrue("传输还被剧集会话用着", connector.closedTransports.isEmpty())

        held.close()
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(listOf("nas:445"), connector.closedTransports)
    }
}
