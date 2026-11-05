package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbMediaReadsTest {

    private val connector = FakeSmbConnector()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val reads = SmbMediaReads(connector, SmbLocator(sources))

    private val root = "smb://nas/media"
    private val movie = "$root/电影.mkv"
    private val payload = ByteArray(4096) { (it % 241).toByte() }

    private suspend fun addSource(url: String, username: String = "cc") {
        sources.add(NetworkBookmark.fromUrl(url, username)!!, password = "secret")
    }

    @Test
    fun `按字节范围读文件开头，句柄用完即关`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        val head = ByteArray(16)
        val read = reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, head, 0, head.size) }

        assertEquals(16, read)
        assertArrayEquals(payload.copyOfRange(0, 16), head)
        assertTrue(connector.handles.getValue("media/电影.mkv").closed)
        assertEquals(listOf("cc" to "secret"), connector.logins)
    }

    @Test
    fun `读尺寸按服务器协商上限给出`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        connector.maxReadSize = 64 * 1024

        val source = reads.openRead(movie, payload.size.toLong())
        try {
            assertEquals(64 * 1024, source.maxReadBytes)
        } finally {
            source.close()
        }
    }

    @Test
    fun `位置不属于书签时给出来源失败`() = runTest {
        val exception = runCatching { reads.openRead(movie, payload.size.toLong()) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `抽帧复用同一会话，不因每个条目重新建连`() = runTest {
        addSource(root)
        val second = "$root/第二集.mkv"
        connector.addFile("media", "电影.mkv", payload)
        connector.addFile("media", "第二集.mkv", payload)

        reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }
        reads.openRead(second, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("两个条目共用一条会话", listOf("media"), connector.shares)
        assertEquals(listOf("cc" to "secret"), connector.logins)
        assertTrue(connector.handles.getValue("media/电影.mkv").closed)
        assertTrue(connector.handles.getValue("media/第二集.mkv").closed)
    }

    @Test
    fun `作废句柄时丢弃会话，下一次打开重新建连`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        val handle = reads.openRead(movie, payload.size.toLong())
        handle.discard()

        assertTrue("作废时句柄要关", connector.handles.getValue("media/电影.mkv").closed)
        reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("作废后必须新建会话", listOf("media", "media"), connector.shares)
    }

    @Test
    fun `打开失败后的重试换新会话`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        connector.openReadFailure = MediaSourceException(MediaFailure.Unreachable, "连接已断")

        runCatching { reads.openRead(movie, payload.size.toLong()) }
        connector.openReadFailure = null
        reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("失败会话不得复用，重试新建一条", listOf("media", "media"), connector.shares)
        assertEquals("失败会话只关一次", 1, connector.closedShares.count { it == "media" })
    }

    @Test
    fun `业务类打开失败也丢弃会话`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        connector.openReadFailure = MediaSourceException(MediaFailure.NotFound, "文件不存在")

        runCatching { reads.openRead(movie, payload.size.toLong()) }
        connector.openReadFailure = null
        reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("打开失败一律丢弃会话，重试新建", listOf("media", "media"), connector.shares)
        assertEquals("失败会话只关一次", 1, connector.closedShares.count { it == "media" })
    }

    @Test
    fun `读取时的业务失败保留会话，会话类失败丢弃`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        val business = reads.openRead(movie, payload.size.toLong())
        connector.readFailure = MediaSourceException(MediaFailure.NotFound, "读不到")
        runCatching { business.readAt(0, ByteArray(4), 0, 4) }
        business.close()
        connector.readFailure = null
        reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("业务失败不动会话", listOf("media"), connector.shares)

        val broken = reads.openRead(movie, payload.size.toLong())
        connector.readFailure = IllegalStateException("传输断开")
        runCatching { broken.readAt(0, ByteArray(4), 0, 4) }
        broken.close()
        connector.readFailure = null
        reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("会话类失败丢弃后新建", listOf("media", "media"), connector.shares)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `打开被取消后作废会话，下一次打开重新建连`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        val gate = CompletableDeferred<Unit>()
        connector.duringOpenRead = { gate.await() }

        val opening = async { reads.openRead(movie, payload.size.toLong()) }
        runCurrent()
        opening.cancelAndJoin()
        connector.duringOpenRead = null

        reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("被取消的打开不得把会话留给下一次重试", listOf("media", "media"), connector.shares)
        assertEquals("取消的会话只关一次", 1, connector.closedShares.count { it == "media" })
    }

    @Test
    fun `淘汰后作废不再重复关闭连接`() = runTest {
        val limited = SmbMediaReads(connector, SmbLocator(sources), maxSessions = 1)
        addSource("smb://nas/a")
        addSource("smb://nas/b")
        connector.addFile("a", "电影.mkv", payload)
        connector.addFile("b", "电影.mkv", payload)

        val a = limited.openRead("smb://nas/a/电影.mkv", payload.size.toLong())
        a.close()
        limited.openRead("smb://nas/b/电影.mkv", payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }
        assertEquals("淘汰关一次 a", listOf("a"), connector.closedShares)

        // 会话已被淘汰关闭：迟到的作废不得再关同一条连接。
        a.discard()
        assertEquals("作废不得重复关闭连接", listOf("a"), connector.closedShares)
    }

    @Test
    fun `会话闲置两分钟后回收`() = runTest {
        var now = 1_000L
        val idle = SmbMediaReads(connector, SmbLocator(sources), clock = { now })
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        idle.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }
        now += 120_000
        idle.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }
        assertEquals("闲置刚到 2 分钟仍沿用会话", listOf("media"), connector.shares)

        now += 120_001
        idle.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }
        assertEquals("闲置超过 2 分钟重新建连", listOf("media", "media"), connector.shares)
    }

    @Test
    fun `会话闲置超时后关闭并重新建连`() = runTest {
        var now = 1_000L
        val idle = SmbMediaReads(connector, SmbLocator(sources), clock = { now }, idleMillis = 1_000)
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        idle.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }
        now += 999
        idle.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }
        assertEquals("闲置未到上限沿用会话", listOf("media"), connector.shares)

        now += 1_002
        idle.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, ByteArray(4), 0, 4) }
        assertEquals("闲置到上限重新建连", listOf("media", "media"), connector.shares)
        assertEquals(1, connector.closeOrder.count { it == "connection" })
    }

    @Test
    fun `会话表超过上限时关掉最久未用的`() = runTest {
        var now = 1_000L
        val limited = SmbMediaReads(connector, SmbLocator(sources), clock = { now }, maxSessions = 2)
        listOf("a", "b", "c").forEach { share ->
            addSource("smb://nas/$share")
            connector.addFile(share, "电影.mkv", payload)
        }

        // 三条会话按不同时刻取用，最久未用的确定为 a。
        limited.openRead("smb://nas/a/电影.mkv", payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }
        now += 1_000
        limited.openRead("smb://nas/b/电影.mkv", payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }
        assertEquals("未超上限不关会话", emptyList<String>(), connector.closedShares)

        now += 1_000
        limited.openRead("smb://nas/c/电影.mkv", payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("超出上限关掉最久未用的 a", listOf("a"), connector.closedShares)
        assertEquals(listOf("a", "b", "c"), connector.shares)
    }

    @Test
    fun `淘汰不关还有句柄在用的会话`() = runTest {
        val limited = SmbMediaReads(connector, SmbLocator(sources), maxSessions = 1)
        listOf("a", "b").forEach { share ->
            addSource("smb://nas/$share")
            connector.addFile(share, "电影.mkv", payload)
        }

        val holding = limited.openRead("smb://nas/a/电影.mkv", payload.size.toLong())
        limited.openRead("smb://nas/b/电影.mkv", payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("a 还有句柄在用，不得被淘汰", emptyList<String>(), connector.closedShares)

        holding.close()
        assertEquals("句柄关闭后会话仍在，等下一次淘汰", emptyList<String>(), connector.closedShares)
    }

    @Test
    fun `还有句柄在用的会话不被闲置关闭`() = runTest {
        var now = 1_000L
        val idle = SmbMediaReads(connector, SmbLocator(sources), clock = { now }, idleMillis = 1_000)
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)

        val holding = idle.openRead(movie, payload.size.toLong())
        now += 10_000
        idle.openRead(movie, payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("有句柄在用时重建连", listOf("media"), connector.shares)
        assertTrue(connector.closedShares.isEmpty())
        holding.close()
    }

    @Test
    fun `同一会话连续打开失败到上限时重建一次客户端`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        connector.openReadFailure = MediaSourceException(MediaFailure.Unreachable, "连接已断")

        repeat(2) { runCatching { reads.openRead(movie, payload.size.toLong()) } }
        assertEquals("未到上限不重建", 0, connector.resets)

        runCatching { reads.openRead(movie, payload.size.toLong()) }
        assertEquals("到上限重建一次", 1, connector.resets)
    }

    @Test
    fun `建连失败同样计入连续失败`() = runTest {
        addSource(root)
        connector.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        repeat(3) { runCatching { reads.openRead(movie, payload.size.toLong()) } }

        assertEquals("建连失败也累计", 1, connector.resets)
    }

    @Test
    fun `打开成功一次后失败计数清零`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        val broken = MediaSourceException(MediaFailure.Unreachable, "连接已断")
        connector.openReadFailure = broken
        repeat(2) { runCatching { reads.openRead(movie, payload.size.toLong()) } }

        connector.openReadFailure = null
        reads.openRead(movie, payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }

        connector.openReadFailure = broken
        repeat(2) { runCatching { reads.openRead(movie, payload.size.toLong()) } }

        assertEquals("中途成功过，失败不累计到上限", 0, connector.resets)
    }

    @Test
    fun `重建后下一次打开重新建连`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        connector.openReadFailure = MediaSourceException(MediaFailure.Unreachable, "连接已断")
        repeat(3) { runCatching { reads.openRead(movie, payload.size.toLong()) } }

        connector.openReadFailure = null
        val head = ByteArray(8)
        reads.openRead(movie, payload.size.toLong()).use { it.readAt(0, head, 0, head.size) }

        assertEquals(1, connector.resets)
        assertEquals("每次失败丢掉会话，重建后再建一条", 4, connector.shares.size)
        assertArrayEquals(payload.copyOfRange(0, 8), head)
    }

    @Test
    fun `业务类失败不计入连续失败`() = runTest {
        addSource(root)

        repeat(3) { runCatching { reads.openRead("$root/不存在.mkv", 1L) } }

        assertEquals("文件不存在不重建客户端", 0, connector.resets)
    }

    @Test
    fun `失败间隔超过时间窗后重新计数`() = runTest {
        var now = 1_000L
        val timed = SmbMediaReads(connector, SmbLocator(sources), clock = { now })
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        connector.openReadFailure = MediaSourceException(MediaFailure.Unreachable, "连接已断")

        repeat(2) { runCatching { timed.openRead(movie, payload.size.toLong()) } }
        now += 60_000 + 1
        runCatching { timed.openRead(movie, payload.size.toLong()) }
        assertEquals("超出时间窗重新计数", 0, connector.resets)

        repeat(2) { runCatching { timed.openRead(movie, payload.size.toLong()) } }
        assertEquals("重新计数后仍能到上限", 1, connector.resets)
    }

    @Test
    fun `重建作废旧客户端上的会话`() = runTest {
        addSource(root)
        val other = "smb://nas/other"
        addSource(other)
        connector.addFile("media", "电影.mkv", payload)
        connector.addFile("other", "电影.mkv", payload)
        val holding = reads.openRead("$other/电影.mkv", payload.size.toLong())
        connector.openReadFailure = MediaSourceException(MediaFailure.Unreachable, "连接已断")

        repeat(3) { runCatching { reads.openRead(movie, payload.size.toLong()) } }
        assertEquals(1, connector.resets)

        // other 的会话由旧客户端建立：作废后必须重新建连，不能复用死连接再失败一次。
        connector.openReadFailure = null
        reads.openRead("$other/电影.mkv", payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("重建后 other 重新建连", listOf("other", "media", "media", "media", "other"), connector.shares)
        holding.close()
    }

    @Test
    fun `建连期间客户端被作废时用新客户端重连`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        var once = true
        connector.duringConnect = {
            if (once) {
                once = false
                connector.reset()
            }
        }

        reads.openRead(movie, payload.size.toLong()).use { it.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("作废一次", 1, connector.resets)
        assertEquals("旧客户端上的连接不登记，重新建连", 2, connector.shares.size)
    }

    @Test
    fun `权限类失败不计入连续失败`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        connector.openReadFailure = MediaSourceException(MediaFailure.Authentication, "登录失败")

        repeat(3) { runCatching { reads.openRead(movie, payload.size.toLong()) } }

        assertEquals("登录失败不重建客户端", 0, connector.resets)
    }

    @Test
    fun `会话失效类失败计入连续失败`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        val sessionDeleted = SMBApiException(
            NtStatus.STATUS_USER_SESSION_DELETED.value,
            SMB2MessageCommandCode.SMB2_CREATE,
            null,
        )
        connector.openReadFailure = MediaSourceException(MediaFailure.Unknown, "会话失效", sessionDeleted)

        repeat(3) { runCatching { reads.openRead(movie, payload.size.toLong()) } }

        assertEquals("服务器删掉会话时重建客户端", 1, connector.resets)
    }

    @Test
    fun `重建时清掉其它键的失败计数`() = runTest {
        addSource(root)
        val other = "smb://nas/other"
        addSource(other)
        connector.addFile("media", "电影.mkv", payload)
        connector.addFile("other", "电影.mkv", payload)
        connector.openReadFailure = MediaSourceException(MediaFailure.Unreachable, "连接已断")

        repeat(2) { runCatching { reads.openRead(movie, payload.size.toLong()) } }
        repeat(3) { runCatching { reads.openRead("$other/电影.mkv", payload.size.toLong()) } }
        assertEquals(1, connector.resets)

        // media 已累计两次失败：重建时不清零的话，再来一次就会立刻再重建。
        runCatching { reads.openRead(movie, payload.size.toLong()) }
        assertEquals("重建后旧计数已清零", 1, connector.resets)

        repeat(2) { runCatching { reads.openRead(movie, payload.size.toLong()) } }
        assertEquals("重新计数后到上限", 2, connector.resets)
    }
}
