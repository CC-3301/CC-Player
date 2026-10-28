package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
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
        val limited = SmbMediaReads(connector, SmbLocator(sources), maxSessions = 2)
        listOf("a", "b", "c").forEach { share ->
            addSource("smb://nas/$share")
            connector.addFile(share, "电影.mkv", payload)
        }

        listOf("a", "b").forEach { share ->
            limited.openRead("smb://nas/$share/电影.mkv", payload.size.toLong())
                .use { source -> source.readAt(0, ByteArray(4), 0, 4) }
        }
        assertEquals("未超上限不关会话", 0, connector.closeOrder.count { it == "connection" })

        limited.openRead("smb://nas/c/电影.mkv", payload.size.toLong())
            .use { source -> source.readAt(0, ByteArray(4), 0, 4) }

        assertEquals("超出上限关掉最久未用的", 1, connector.closeOrder.count { it == "connection" })
        assertEquals(listOf("a", "b", "c"), connector.shares)
    }
}
