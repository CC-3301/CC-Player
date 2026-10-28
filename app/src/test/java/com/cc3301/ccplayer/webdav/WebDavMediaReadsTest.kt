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
        val read = reads.openRead(movie, payload.size.toLong()).use { source -> source.readAt(0, head, 0, head.size) }

        assertEquals(16, read)
        assertArrayEquals(payload.copyOfRange(0, 16), head)
        assertTrue(transport.handles.getValue(movie).closed)
        assertEquals(listOf("cc" to "secret"), transport.logins)
    }

    @Test
    fun `把目录给出的文件大小交给读取句柄`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)

        val size = reads.openRead(movie, payload.size.toLong()).use { source -> source.size }

        assertEquals(payload.size.toLong(), size)
        assertEquals(listOf(payload.size.toLong()), transport.openedSizes)
    }

    @Test
    fun `位置不属于书签时给出来源失败`() = runTest {
        val exception = runCatching { reads.openRead(movie, payload.size.toLong()) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `服务器忽略 Range 时该网络源此后不再打开句柄`() = runTest {
        addSource(root)
        transport.addFile(movie, payload)
        transport.addFile("$root/另一集.mkv", payload)
        transport.ignoreRange = true

        val first = reads.openRead(movie, payload.size.toLong())
        val failure = runCatching { first.readAt(0, ByteArray(4), 0, 4) }.exceptionOrNull()
        first.close()

        assertTrue("首次探测即记下结论", failure is WebDavRangeUnsupportedException)
        assertEquals(1, transport.openedSizes.size)

        val second = runCatching { reads.openRead("$root/另一集.mkv", payload.size.toLong()) }.exceptionOrNull()

        assertTrue(second is WebDavRangeUnsupportedException)
        assertEquals("同一源的后续读取不再打开句柄", 1, transport.openedSizes.size)
    }

    @Test
    fun `同一主机上的另一个书签不受区间结论影响`() = runTest {
        val otherRoot = "https://dav.example.com/remote.php/dav/files/other"
        val otherMovie = "$otherRoot/电影.mkv"
        addSource(root)
        addSource(otherRoot, username = "dd")
        transport.addFile(movie, payload)
        transport.addFile(otherMovie, payload)
        transport.ignoreRange = true

        reads.openRead(movie, payload.size.toLong()).use { source ->
            assertTrue(runCatching { source.readAt(0, ByteArray(4), 0, 4) }.exceptionOrNull() is WebDavRangeUnsupportedException)
        }

        // 另一个书签各记各的结论：仍会打开句柄并再探测一次。
        val other = reads.openRead(otherMovie, payload.size.toLong())
        val failure = runCatching { other.readAt(0, ByteArray(4), 0, 4) }.exceptionOrNull()
        other.close()

        assertTrue(failure is WebDavRangeUnsupportedException)
        assertEquals(2, transport.openedSizes.size)
    }
}
