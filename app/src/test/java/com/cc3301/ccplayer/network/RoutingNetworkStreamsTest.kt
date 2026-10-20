package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.streaming.ByteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 记录调用与关闭次数的假回环代理。 */
private class FakeNetworkStreams : NetworkStreams {

    val probed = mutableListOf<String>()
    val opened = mutableListOf<String>()

    var closes = 0

    override suspend fun probe(location: String) {
        probed += location
    }

    override fun urlFor(location: String): String = "http://127.0.0.1:1/${location.substringAfterLast('/')}"

    override suspend fun openRead(location: String): ByteSource {
        opened += location
        return object : ByteSource {
            override val size: Long = 0
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = 0
            override fun close() = Unit
        }
    }

    override fun close() {
        closes++
    }
}

class RoutingNetworkStreamsTest {

    private val smb = FakeNetworkStreams()
    private val webDav = FakeNetworkStreams()
    private val streams = RoutingNetworkStreams(mapOf("smb" to smb, "http" to webDav, "https" to webDav))

    @Test
    fun `试连按方案分派`() = runTest {
        streams.probe("smb://nas/media/电影.mkv")
        streams.probe("https://dav.example.com/dav/电影.mkv")

        assertEquals(listOf("smb://nas/media/电影.mkv"), smb.probed)
        assertEquals(listOf("https://dav.example.com/dav/电影.mkv"), webDav.probed)
    }

    @Test
    fun `本机地址按方案分派`() {
        assertEquals("http://127.0.0.1:1/电影.mkv", streams.urlFor("smb://nas/media/电影.mkv"))
        assertEquals("http://127.0.0.1:1/电影.mkv", streams.urlFor("http://dav.example.com/dav/电影.mkv"))
    }

    @Test
    fun `预览读取按方案分派`() = runTest {
        streams.openRead("smb://nas/media/电影.mkv").close()
        streams.openRead("https://dav.example.com/dav/歌.mp3").close()

        assertEquals(listOf("smb://nas/media/电影.mkv"), smb.opened)
        assertEquals(listOf("https://dav.example.com/dav/歌.mp3"), webDav.opened)
    }

    @Test
    fun `方案大小写不影响分派`() {
        assertEquals("http://127.0.0.1:1/电影.mkv", streams.urlFor("SMB://nas/media/电影.mkv"))
    }

    @Test
    fun `未知方案试连给出找不到原因`() = runTest {
        val exception = runCatching { streams.probe("ftp://nas/media/电影.mkv") }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `未知方案取本机地址给出找不到原因`() {
        val exception = runCatching { streams.urlFor("ftp://nas/media/电影.mkv") }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }

    @Test
    fun `关闭时同一个代理只关一次`() {
        streams.close()

        assertEquals(1, smb.closes)
        assertEquals(1, webDav.closes)
    }
}
