package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 记录调用与关闭次数的假回环代理。 */
private class FakeNetworkStreams : NetworkStreams {

    val probed = mutableListOf<String>()

    /** 本协议名下已就绪的位置：不在保留集合里的会被关掉。 */
    val ready = mutableSetOf<String>()

    val returned = mutableListOf<String>()

    /** 被叫来声明保留集合的次数。 */
    var retainCalls = 0

    var discards = 0

    var preparedDiscards = 0

    var closes = 0

    override fun prepare(location: String): PreparedSource = object : PreparedSource {
        override suspend fun probe(location: String) {
            probed += location
        }

        override fun discard() {
            preparedDiscards++
        }
    }

    override fun urlFor(location: String): String = "http://127.0.0.1:1/${location.substringAfterLast('/')}"

    override fun retain(locations: Set<String>) {
        retainCalls++
        val dropped = ready.filterNot { it in locations }
        ready.removeAll(dropped.toSet())
        returned += dropped
    }

    override fun discardByteSources() {
        discards++
    }

    override fun close() {
        closes++
    }
}

/** 一次准备加一次试连：测试只关心分派时用。 */
private suspend fun NetworkStreams.probeForTest(location: String) {
    prepare(location).probe(location)
}

class RoutingNetworkStreamsTest {

    private val smb = FakeNetworkStreams()
    private val webDav = FakeNetworkStreams()
    private val streams = RoutingNetworkStreams(mapOf("smb" to smb, "http" to webDav, "https" to webDav))

    @Test
    fun `试连按方案分派`() = runTest {
        streams.probeForTest("smb://nas/media/电影.mkv")
        streams.probeForTest("https://dav.example.com/dav/电影.mkv")

        assertEquals(listOf("smb://nas/media/电影.mkv"), smb.probed)
        assertEquals(listOf("https://dav.example.com/dav/电影.mkv"), webDav.probed)
    }

    @Test
    fun `本机地址按方案分派`() {
        assertEquals("http://127.0.0.1:1/电影.mkv", streams.urlFor("smb://nas/media/电影.mkv"))
        assertEquals("http://127.0.0.1:1/电影.mkv", streams.urlFor("http://dav.example.com/dav/电影.mkv"))
    }

    @Test
    fun `方案大小写不影响分派`() {
        assertEquals("http://127.0.0.1:1/电影.mkv", streams.urlFor("SMB://nas/media/电影.mkv"))
    }

    @Test
    fun `未知方案试连给出找不到原因`() = runTest {
        val exception = runCatching { streams.probeForTest("ftp://nas/media/电影.mkv") }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `未知方案取本机地址给出找不到原因`() {
        val exception = runCatching { streams.urlFor("ftp://nas/media/电影.mkv") }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `保留集合只归还被移出的位置`() {
        val kept = "smb://nas/media/第一部.mkv"
        val removed = "smb://nas/media/第二部.mkv"
        smb.ready += kept
        smb.ready += removed

        streams.retain(setOf(kept, removed))
        streams.retain(setOf(kept))

        assertEquals(listOf(removed), smb.returned)
        assertEquals(setOf(kept), smb.ready)
    }

    @Test
    fun `保留集合未变化时不动作`() {
        val first = "smb://nas/media/第一部.mkv"
        val second = "smb://nas/media/第二部.mkv"
        smb.ready += first
        smb.ready += second

        streams.retain(setOf(first, second))
        streams.retain(setOf(first))
        streams.retain(setOf(first))

        assertEquals("集合未变化时不叫协议", 1, smb.retainCalls)
        assertEquals(listOf(second), smb.returned)
    }

    @Test
    fun `保留集合按方案分派`() {
        val smbMovie = "smb://nas/media/电影.mkv"
        val davMovie = "https://dav.example.com/dav/电影.mkv"
        smb.ready += smbMovie
        webDav.ready += davMovie

        streams.retain(setOf(smbMovie, davMovie))
        streams.retain(emptySet())

        assertEquals(listOf(smbMovie), smb.returned)
        assertEquals(listOf(davMovie), webDav.returned)
    }

    @Test
    fun `试连的凭证按方案分派`() {
        val prepared = streams.prepare("smb://nas/media/电影.mkv")
        prepared.discard()

        assertEquals("凭证只释放自己那条", 1, smb.preparedDiscards)
        assertEquals(0, webDav.preparedDiscards)
    }

    @Test
    fun `关闭时同一个代理只关一次`() {
        streams.close()

        assertEquals(1, smb.closes)
        assertEquals(1, webDav.closes)
    }
}
