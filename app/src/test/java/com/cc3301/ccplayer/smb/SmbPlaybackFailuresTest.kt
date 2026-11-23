package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.FakeBookmarkStore
import com.cc3301.ccplayer.network.FakeCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放读失败的分类：播放控制层只看这条结论决定换传输恢复还是既有失败收尾。
 * 记录只反映还没恢复的那次失败，浏览与封面的读不参与。
 */
class SmbPlaybackFailuresTest {

    private val connector = FakeSmbConnector()
    private val sources = NetworkSourceRepository(FakeBookmarkStore(), FakeCredentialStore())
    private val sessions = SmbSessions(connector)
    private val locator = SmbLocator(sources)

    private val root = "smb://nas/media"
    private val movie = "$root/电影.mkv"
    private val payload = ByteArray(4096) { (it % 241).toByte() }

    private suspend fun target(): SmbTarget = locator.locate(movie)!!

    private suspend fun addSource() {
        sources.add(NetworkBookmark.fromUrl(root, "cc")!!, password = "secret")
        connector.addFile("media", "电影.mkv", payload)
    }

    @Test
    fun `播放读的本地传输失败记成换传输可恢复的分类`() = runTest {
        addSource()
        connector.readFailure = IOException("读超时")
        val source = sessions.openPlaybackRead(target())

        runCatching { source.readAt(0, ByteArray(16), 0, 16) }

        assertEquals(SmbPlaybackFailure.Transport, sessions.playbackFailure(movie))
    }

    @Test
    fun `播放读打开成功清掉上一次失败分类`() = runTest {
        addSource()
        connector.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")
        runCatching { sessions.openPlaybackRead(target()) }
        assertEquals(SmbPlaybackFailure.Unreachable, sessions.playbackFailure(movie))

        connector.failure = null
        sessions.openPlaybackRead(target()).close()

        assertNull(sessions.playbackFailure(movie))
    }

    @Test
    fun `播放读打开连不上记成网络服务器终态`() = runTest {
        addSource()
        connector.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")

        runCatching { sessions.openPlaybackRead(target()) }

        assertEquals(SmbPlaybackFailure.Unreachable, sessions.playbackFailure(movie))
    }

    @Test
    fun `浏览读的失败不参与播放的传输恢复判定`() = runTest {
        addSource()
        connector.readFailure = IOException("读超时")
        val source = sessions.openRead(target())

        runCatching { source.readAt(0, ByteArray(16), 0, 16) }

        assertNull(sessions.playbackFailure(movie))
    }
}
