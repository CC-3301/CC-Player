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
        val read = reads.openRead(movie).use { source -> source.readAt(0, head, 0, head.size) }

        assertEquals(16, read)
        assertArrayEquals(payload.copyOfRange(0, 16), head)
        assertTrue(connector.handles.getValue("media/电影.mkv").closed)
        assertEquals(listOf("cc" to "secret"), connector.logins)
    }

    @Test
    fun `位置不属于书签时给出来源失败`() = runTest {
        val exception = runCatching { reads.openRead(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `并发分组键取最长匹配的书签`() = runTest {
        val rootA = "smb://nas/media/A"
        val rootB = "smb://nas/media/B"
        addSource(rootA)
        addSource(rootB)

        assertEquals(rootA, reads.sourceKey("$rootA/电影.mkv"))
        assertEquals(rootB, reads.sourceKey("$rootB/电影.mkv"))
    }
}
