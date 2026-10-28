package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.streaming.ByteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 记录打开位置的假读取接缝。 */
private class RecordingMediaReads : NetworkMediaReads {

    val opened = mutableListOf<String>()
    val sizes = mutableListOf<Long>()

    override suspend fun openRead(location: String, size: Long): ByteSource {
        opened += location
        sizes += size
        return object : ByteSource {
            override val size: Long = 0
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = 0
            override fun close() = Unit
        }
    }
}

class RoutingMediaReadsTest {

    private val smb = RecordingMediaReads()
    private val webDav = RecordingMediaReads()
    private val reads = RoutingMediaReads(mapOf("smb" to smb, "http" to webDav, "https" to webDav))

    @Test
    fun `按方案分派读取`() = runTest {
        reads.openRead("smb://nas/media/电影.mkv", 4096).close()
        reads.openRead("https://dav.example.com/dav/歌.mp3", 1024).close()

        assertEquals(listOf("smb://nas/media/电影.mkv"), smb.opened)
        assertEquals(listOf(4096L), smb.sizes)
        assertEquals(listOf("https://dav.example.com/dav/歌.mp3"), webDav.opened)
        assertEquals(listOf(1024L), webDav.sizes)
    }

    @Test
    fun `未知方案给出来源失败`() = runTest {
        val exception = runCatching { reads.openRead("ftp://nas/media/电影.mkv", 0) }.exceptionOrNull()

        assertEquals(MediaFailure.NotFound, (exception as MediaSourceException).failure)
    }
}
