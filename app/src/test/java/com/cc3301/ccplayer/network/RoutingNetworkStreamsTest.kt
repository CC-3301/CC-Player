package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 记录调用与关闭次数的假回环代理。 */
private class FakeNetworkStreams : NetworkStreams {

    val probed = mutableListOf<String>()

    val returned = mutableListOf<String>()

    var discards = 0

    var preparedDiscards = 0

    var closes = 0

    override fun prepare(location: String): PreparedSource = object : PreparedSource {
        override fun discard() {
            preparedDiscards++
        }
    }

    override suspend fun probe(location: String, prepared: PreparedSource) {
        probed += location
    }

    override fun urlFor(location: String): String = "http://127.0.0.1:1/${location.substringAfterLast('/')}"

    override fun returnSource(location: String) {
        returned += location
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
    probe(location, prepare(location))
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
    fun `归还按方案分派`() {
        streams.returnSource("smb://nas/media/电影.mkv")
        streams.returnSource("https://dav.example.com/dav/电影.mkv")

        assertEquals(listOf("smb://nas/media/电影.mkv"), smb.returned)
        assertEquals(listOf("https://dav.example.com/dav/电影.mkv"), webDav.returned)
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
