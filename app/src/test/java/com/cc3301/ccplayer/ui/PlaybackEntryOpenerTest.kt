package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.testVideo
import com.cc3301.ccplayer.network.NetworkStreams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

/** 记录入口实际产生的界面动作。 */
private class EntryRecorder {
    val events = mutableListOf<String>()
    val enterStates = mutableListOf<Boolean>()
    var opened: OpenedPlayback? = null
    var shownFailure: MediaFailure? = null
    var leaveCount = 0

    fun enter(preparing: Boolean) {
        enterStates += preparing
        events += "enter:$preparing"
    }

    fun start(current: MediaFile, playlist: List<MediaFile>, directoryEntries: List<MediaFile>) {
        opened = OpenedPlayback(current, playlist, directoryEntries)
        events += "start"
    }

    fun showFailure(failure: MediaFailure) {
        shownFailure = failure
        events += "failure:$failure"
    }

    fun leave() {
        leaveCount += 1
        events += "leave"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEntryOpenerTest {

    private val networkVideo = testVideo("第1集.mkv", "smb://nas/media/第1集.mkv")
        .copy(source = MediaSource.Network)
    private val subtitle = testVideo("第1集.srt", "smb://nas/media/第1集.srt")
        .copy(source = MediaSource.Network)

    private fun opener(
        scope: kotlinx.coroutines.CoroutineScope,
        recorder: EntryRecorder,
        directoryEntries: suspend () -> List<MediaFile>,
        streams: () -> NetworkStreams,
    ): PlaybackEntryOpener = PlaybackEntryOpener(
        scope = scope,
        directoryEntries = directoryEntries,
        networkStreams = streams,
        onEnterPlayer = recorder::enter,
        onStartPlayback = recorder::start,
        onShowFailure = recorder::showFailure,
        onLeavePlayer = recorder::leave,
    )

    @Test
    fun `视频点击后立即进入播放页再等待目录与网络探测`() = runTest {
        val streams = GatedNetworkStreams()
        val directoryGate = CompletableDeferred<Unit>()
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = {
                directoryGate.await()
                listOf(subtitle)
            },
            streams = { streams },
        )

        opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)

        assertEquals(listOf(true), recorder.enterStates)
        assertNull(recorder.opened)

        directoryGate.complete(Unit)
        runCurrent()
        assertNull("网络探测完成前不起播", recorder.opened)

        streams.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(networkVideo.location), streams.probed)
        val result = requireNotNull(recorder.opened)
        assertEquals("http://127.0.0.1:1/第1集.mkv", result.current.playbackLocation)
        assertEquals(listOf("http://127.0.0.1:1/第1集.mkv"), result.playlist.map { it.playbackLocation })
        assertEquals(listOf("http://127.0.0.1:1/第1集.srt"), result.directoryEntries.map { it.playbackLocation })
        assertEquals(0, recorder.leaveCount)
    }

    @Test
    fun `音频准备完成后才进入播放页`() = runTest {
        val audio = testVideo("片头曲.mp3", "content://root/片头曲.mp3")
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { listOf(audio) },
            streams = { throw AssertionError("本地音频不应建立网络连接") },
        )

        opener.open(audio, listOf(audio), enterBeforePreparing = false)

        assertTrue("音频不预先进入播放页", recorder.enterStates.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf(false), recorder.enterStates)
        assertEquals(listOf(audio), recorder.opened?.playlist)
    }

    @Test
    fun `网络探测失败提示原因并退回列表`() = runTest {
        val streams = GatedNetworkStreams()
        streams.gate.complete(Unit)
        streams.failure = MediaSourceException(MediaFailure.Authentication, "登录被拒")
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { emptyList() },
            streams = { streams },
        )

        opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)
        advanceUntilIdle()

        assertNull(recorder.opened)
        assertEquals(MediaFailure.Authentication, recorder.shownFailure)
        assertEquals(1, recorder.leaveCount)
        assertEquals(listOf("enter:true", "failure:Authentication", "leave"), recorder.events)
    }

    @Test
    fun `目录读取异常按未知原因退回列表`() = runTest {
        val localVideo = testVideo("本地.mkv", "content://root/本地.mkv")
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { throw IllegalStateException("目录读不了") },
            streams = { throw AssertionError("本地媒体不应建立网络连接") },
        )

        opener.open(localVideo, listOf(localVideo), enterBeforePreparing = true)
        advanceUntilIdle()

        assertEquals(MediaFailure.Unknown, recorder.shownFailure)
        assertEquals(1, recorder.leaveCount)
        assertNull(recorder.opened)
    }

    @Test
    fun `探测中取消后不再起播`() = runTest {
        val streams = GatedNetworkStreams()
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { emptyList() },
            streams = { streams },
        )

        opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)
        runCurrent()
        opener.cancel()
        streams.gate.complete(Unit)
        advanceUntilIdle()

        assertNull(recorder.opened)
        assertNull(recorder.shownFailure)
        assertEquals(0, recorder.leaveCount)
    }

    @Test
    fun `连续点开只保留后一次`() = runTest {
        val streams = GatedNetworkStreams()
        val second = testVideo("第2集.mkv", "smb://nas/media/第2集.mkv")
            .copy(source = MediaSource.Network)
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { emptyList() },
            streams = { streams },
        )

        opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)
        runCurrent()
        opener.open(second, listOf(second), enterBeforePreparing = true)
        runCurrent()
        streams.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(second.location, recorder.opened?.current?.location)
        assertEquals(listOf(second.location), streams.probed)
    }
}
