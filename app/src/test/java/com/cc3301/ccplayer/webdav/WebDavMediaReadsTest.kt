package com.cc3301.ccplayer.webdav

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

class WebDavMediaReadsTest {

    private val transport = FakeWebDavTransport()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val reads = WebDavMediaReads(transport, WebDavLocator(sources))

    private val root = "https://dav.example.com/remote.php/dav/files/cc"
    private val movie = "$root/电影.mkv"
    private val payload = ByteArray(4096) { (it % 241).toByte() }

    private suspend fun addSource(url: String, username: String = "cc") {
        sources.add(NetworkBookmark.fromUrl(url, username)!!, password = "secret")
    }

    @Test
    fun `按字节范围读文件开头，句柄用完即关`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)

        val head = ByteArray(16)
        val read = reads.openRead(movie).use { source -> source.readAt(0, head, 0, head.size) }

        assertEquals(16, read)
        assertArrayEquals(payload.copyOfRange(0, 16), head)
        assertTrue(transport.handles.getValue(movie).closed)
        assertEquals(listOf("cc" to "secret"), transport.logins)
    }

    @Test
    fun `位置不属于书签时给出来源失败`() = runTest {
        val exception = runCatching { reads.openRead(movie) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `并发分组键取最长匹配的书签`() = runTest {
        val rootA = "https://dav.example.com/remote.php/dav/files/cc/A"
        val rootB = "https://dav.example.com/remote.php/dav/files/cc/B"
        addSource(rootA)
        addSource(rootB)

        assertEquals(rootA, reads.sourceKey("$rootA/电影.mkv"))
        assertEquals(rootB, reads.sourceKey("$rootB/电影.mkv"))
    }
}
