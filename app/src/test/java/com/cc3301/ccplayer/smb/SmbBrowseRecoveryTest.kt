package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.DirectoryMediaLibrary
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.hierynomus.protocol.transport.TransportException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览目录读取的自动恢复：本地 SMB 传输失效时列表自己恢复，失败不到界面上。
 * 没有播放读取租约时换传输；播放持有读取租约时只作废自己的会话、在同一传输上重发一次，
 * 不换也不关共享传输。重发只有一次，不会无限重试。
 */
class SmbBrowseRecoveryTest {

    private val connector = FakeSmbConnector()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val sessions = SmbSessions(connector)
    private val locator = SmbLocator(sources)
    private val library = DirectoryMediaLibrary(SmbMediaFileTree(sessions, locator))

    private val root = "smb://nas/media"
    private val movie = "$root/电影.mkv"
    private val directory = MediaFile(
        name = "media",
        location = root,
        isDirectory = true,
        source = MediaSource.Network,
    )
    private val payload = ByteArray(4096) { (it % 241).toByte() }

    private suspend fun addSource() {
        sources.add(NetworkBookmark.fromUrl(root, "cc")!!, password = "secret")
        connector.addDirectory("media", "", fileEntry("电影.mkv"))
        connector.addFile("media", "电影.mkv", payload)
    }

    private suspend fun playbackRead() = sessions.openPlaybackRead(locator.locate(movie)!!)

    @Test
    fun `没有播放租约时目录读取遇本地传输失效自动换传输恢复`() = runTest {
        addSource()
        connector.listFailure = TransportException(TimeoutException("读超时"))
        connector.listFailureTimes = 1

        val entries = library.list(directory).getOrThrow()

        assertEquals(listOf(movie), entries.map { it.location })
        assertEquals("换了一条传输后自动恢复", 2, connector.transports.size)
    }

    @Test
    fun `播放持有租约时目录读取只作废自己的会话再试一次`() = runTest {
        addSource()
        val playback = playbackRead()
        connector.listFailure = TransportException(TimeoutException("读超时"))
        connector.listFailureTimes = 1

        val entries = library.list(directory).getOrThrow()

        assertEquals(listOf(movie), entries.map { it.location })
        assertEquals("不换传输", 1, connector.transports.size)
        assertTrue("不关传输", connector.closedTransports.isEmpty())
        // 浏览侧不碰播放那条会话：自己新建一条，重发再建一条，共三次握手。
        assertEquals("只重发一次", 3, connector.shares.size)
        playback.close()
    }

    @Test
    fun `播放持租约时存活确认判出本地传输失效也在同一传输上重建会话`() = runTest {
        addSource()
        val playback = playbackRead()
        connector.pingFailure = TransportException(TimeoutException("读超时"))

        val entries = library.list(directory).getOrThrow()

        assertEquals(listOf(movie), entries.map { it.location })
        assertEquals("不换传输", 1, connector.transports.size)
        assertTrue("不关传输", connector.closedTransports.isEmpty())
        playback.close()
    }

    @Test
    fun `播放持有租约时重发再失败只报一次错`() = runTest {
        addSource()
        val playback = playbackRead()
        connector.listFailure = TransportException(TimeoutException("读超时"))

        val result = library.list(directory)

        assertTrue("失败交给列表显示", result.isFailure)
        // 浏览侧不碰播放那条会话：自己新建一条，重发再建一条，共三次握手。
        assertEquals("只重发一次", 3, connector.shares.size)
        assertEquals("不换传输", 1, connector.transports.size)
        assertTrue("不关传输", connector.closedTransports.isEmpty())
        playback.close()
    }
}
