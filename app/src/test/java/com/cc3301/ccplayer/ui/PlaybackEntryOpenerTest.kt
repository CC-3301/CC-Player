package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.library.testVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class GatedNetworkStreams : NetworkStreams {

    val gate = CompletableDeferred<Unit>()

    var failure: MediaSourceException? = null

    val probed = mutableListOf<String>()

    override suspend fun probe(location: String) {
        gate.await()
        probed += location
        failure?.let { throw it }
    }

    override fun urlFor(location: String): String = "http://127.0.0.1:1/${location.substringAfterLast('/')}"

    override fun close() = Unit
}

private data class OpenedPlayback(
    val current: MediaFile,
    val playlist: List<MediaFile>,
    val directoryEntries: List<MediaFile>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEntryOpenerTest {

    private val networkVideo = testVideo("第1集.mkv", "smb://nas/media/第1集.mkv")
        .copy(source = MediaSource.Network)
    private val subtitle = testVideo("第1集.srt", "smb://nas/media/第1集.srt")
        .copy(source = MediaSource.Network)

    @Test
    fun `点击后立即进入播放页再等待目录与网络探测`() = runTest {
        val streams = GatedNetworkStreams()
        val directoryGate = CompletableDeferred<Unit>()
        var entered = false
        var opened: OpenedPlayback? = null
        val opener = PlaybackEntryOpener(
            scope = this,
            directoryEntries = {
                directoryGate.await()
                listOf(subtitle)
            },
            networkStreams = { streams },
            onEnterPlayer = { entered = true },
            onReady = { current, playlist, directoryEntries ->
                opened = OpenedPlayback(current, playlist, directoryEntries)
            },
            onFailure = { failure -> throw AssertionError("不应失败：$failure") },
        )

        opener.open(networkVideo, listOf(networkVideo))

        assertTrue("进入播放页不能等后台准备", entered)
        assertNull(opened)

        directoryGate.complete(Unit)
        runCurrent()
        assertNull("网络探测完成前不起播", opened)

        streams.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(networkVideo.location), streams.probed)
        val result = requireNotNull(opened)
        assertEquals("http://127.0.0.1:1/第1集.mkv", result.current.playbackLocation)
        assertEquals(listOf("http://127.0.0.1:1/第1集.mkv"), result.playlist.map { it.playbackLocation })
        assertEquals(listOf("http://127.0.0.1:1/第1集.srt"), result.directoryEntries.map { it.playbackLocation })
    }

    @Test
    fun `网络探测失败回到列表并给出来源原因`() = runTest {
        val streams = GatedNetworkStreams()
        streams.gate.complete(Unit)
        streams.failure = MediaSourceException(MediaFailure.Authentication, "登录被拒")
        var entered = false
        var opened: OpenedPlayback? = null
        var failure: MediaFailure? = null
        val opener = PlaybackEntryOpener(
            scope = this,
            directoryEntries = { emptyList() },
            networkStreams = { streams },
            onEnterPlayer = { entered = true },
            onReady = { current, playlist, directoryEntries ->
                opened = OpenedPlayback(current, playlist, directoryEntries)
            },
            onFailure = { failure = it },
        )

        opener.open(networkVideo, listOf(networkVideo))
        advanceUntilIdle()

        assertTrue(entered)
        assertNull(opened)
        assertEquals(MediaFailure.Authentication, failure)
    }

    @Test
    fun `本地媒体只后台读取目录不从网络探测`() = runTest {
        val localVideo = testVideo("本地.mkv", "content://root/本地.mkv")
        var opened: OpenedPlayback? = null
        val opener = PlaybackEntryOpener(
            scope = this,
            directoryEntries = { listOf(localVideo) },
            networkStreams = { throw AssertionError("本地媒体不应建立网络连接") },
            onEnterPlayer = {},
            onReady = { current, playlist, directoryEntries ->
                opened = OpenedPlayback(current, playlist, directoryEntries)
            },
            onFailure = { failure -> throw AssertionError("不应失败：$failure") },
        )

        opener.open(localVideo, listOf(localVideo))
        advanceUntilIdle()

        val result = requireNotNull(opened)
        assertEquals(localVideo, result.current)
        assertEquals(listOf(localVideo), result.playlist)
        assertEquals(listOf(localVideo), result.directoryEntries)
    }

    @Test
    fun `取消后不再起播`() = runTest {
        val streams = GatedNetworkStreams()
        var opened: OpenedPlayback? = null
        var failed = false
        val opener = PlaybackEntryOpener(
            scope = this,
            directoryEntries = { emptyList() },
            networkStreams = { streams },
            onEnterPlayer = {},
            onReady = { current, playlist, directoryEntries ->
                opened = OpenedPlayback(current, playlist, directoryEntries)
            },
            onFailure = { failed = true },
        )

        opener.open(networkVideo, listOf(networkVideo))
        opener.cancel()
        streams.gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(failed)
        assertNull(opened)
    }
}
