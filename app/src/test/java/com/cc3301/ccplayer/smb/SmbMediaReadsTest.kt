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
import org.junit.Test

class SmbMediaReadsTest {

    private val connector = FakeSmbConnector()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val sessions = SmbSessions(connector)
    private val reads = SmbMediaReads(sessions, SmbLocator(sources))

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

        val source = reads.openRead(movie, payload.size.toLong())
        val buffer = ByteArray(16)
        val read = source.readAt(100, buffer, 0, buffer.size)
        source.close()

        assertEquals(16, read)
        assertArrayEquals(payload.copyOfRange(100, 116), buffer)
        assertEquals(true, connector.handles.getValue("media/电影.mkv").closed)
    }

    @Test
    fun `读尺寸按服务器协商上限给出`() = runTest {
        addSource(root)
        connector.addFile("media", "电影.mkv", payload)
        connector.maxReadSize = 64 * 1024

        val source = reads.openRead(movie, payload.size.toLong())

        assertEquals(64 * 1024, source.maxReadBytes)
        source.close()
    }

    @Test
    fun `位置不属于书签时给出来源失败`() = runTest {
        val failure = runCatching { reads.openRead(movie, payload.size.toLong()) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (failure as MediaSourceException).failure)
    }

    @Test
    fun `抽帧关闭句柄后会话留给下一条目`() = runTest {
        addSource(root)
        connector.addFile("media", "第一集.mkv", payload)
        connector.addFile("media", "第二集.mkv", payload)

        reads.openRead("$root/第一集.mkv", payload.size.toLong()).close()
        reads.openRead("$root/第二集.mkv", payload.size.toLong()).close()

        assertEquals("同一会话复用", 1, connector.shares.size)
        assertEquals("连接不随句柄关闭", emptyList<String>(), connector.closedShares)
    }
}
