package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.MemoryDiagnosticsSink
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.testVideo
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.network.PreparedSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    /** 探测真正完成（门放行后）的时刻；用来在准备返回前插入另一次打开。 */
    var onProbed: (() -> Unit)? = null

    override fun prepare(location: String): PreparedSource = object : PreparedSource {
        override suspend fun probe(location: String) {
            gate.await()
            probed += location
            failure?.let { throw it }
        }

        override fun discard() {
            preparedDiscards++
        }
    }

    override fun urlFor(location: String): String = "http://127.0.0.1:1/${location.substringAfterLast('/')}"

    /** 探测预置句柄被取消打开丢掉的次数。 */
    var preparedDiscards = 0

    override fun returnSource(location: String) = Unit

    override fun discardByteSources() = Unit

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
    var enterCount = 0
    var opened: OpenedPlayback? = null
    var openedGeneration: Int? = null
    var shownFailure: MediaFailure? = null
    var leaveCount = 0

    fun enter() {
        enterCount += 1
        events += "enter"
    }

    fun start(current: MediaFile, playlist: List<MediaFile>, directoryEntries: List<MediaFile>, generation: Int) {
        opened = OpenedPlayback(current, playlist, directoryEntries)
        openedGeneration = generation
        events += "start:$generation"
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

    private val entryState = PlaybackEntryState()

    private val networkVideo = testVideo("第1集.mkv", "smb://nas/media/第1集.mkv")
        .copy(source = MediaSource.Network)
    private val subtitle = testVideo("第1集.srt", "smb://nas/media/第1集.srt")
        .copy(source = MediaSource.Network)

    private fun opener(
        scope: kotlinx.coroutines.CoroutineScope,
        recorder: EntryRecorder,
        directoryEntries: suspend () -> List<MediaFile>,
        streams: () -> NetworkStreams,
        diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    ): PlaybackEntryOpener = PlaybackEntryOpener(
        scope = scope,
        entryState = entryState,
        directoryEntries = directoryEntries,
        networkStreams = streams,
        onEnterPlayer = recorder::enter,
        onStartPlayback = recorder::start,
        onShowFailure = recorder::showFailure,
        onLeavePlayer = recorder::leave,
        diagnostics = diagnostics,
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

        val generation = opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)

        assertEquals(1, recorder.enterCount)
        assertEquals(EntryPhase.Preparing, entryState.phase)
        assertNull(recorder.opened)

        directoryGate.complete(Unit)
        runCurrent()
        assertNull("网络探测完成前不起播", recorder.opened)

        streams.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(networkVideo.location), streams.probed)
        val result = requireNotNull(recorder.opened)
        assertEquals(EntryPhase.AwaitingHandover, entryState.phase)
        assertEquals(generation, recorder.openedGeneration)
        assertEquals("http://127.0.0.1:1/第1集.mkv", result.current.playbackLocation)
        assertEquals(listOf("http://127.0.0.1:1/第1集.mkv"), result.playlist.map { it.playbackLocation })
        assertEquals(listOf("http://127.0.0.1:1/第1集.srt"), result.directoryEntries.map { it.playbackLocation })
        assertEquals(0, recorder.leaveCount)
    }

    @Test
    fun `取消打开丢掉探测预置的读句柄`() = runTest {
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
        // 探测还卡在门里：这次打开的预置句柄当场丢掉，不等下一次探测或关闭。
        opener.cancel()

        assertEquals(1, streams.preparedDiscards)
        assertNull(recorder.opened)
    }

    @Test
    fun `准备回来时已经开始了另一次打开，预置句柄一并丢掉`() = runTest {
        val stale = GatedNetworkStreams()
        val fresh = GatedNetworkStreams()
        val recorder = EntryRecorder()
        var current = stale
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { emptyList() },
            streams = { current },
        )

        opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)
        runCurrent()
        // 门还没放行就开始了另一次打开：前一次回来时代际已不符。
        current = fresh
        fresh.gate.complete(Unit)
        opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)
        stale.gate.complete(Unit)
        runCurrent()

        assertEquals("作废的那次打开要把预置句柄丢掉", 1, stale.preparedDiscards)
        // 只有后一次打开落到播放，作废的那次不起播。
        assertEquals(1, recorder.events.count { it.startsWith("start:") })
        assertEquals(listOf(networkVideo.location), fresh.probed)
        assertTrue(stale.probed.isEmpty())
    }

    @Test
    fun `前一次打开作废时不丢后一次刚探好的句柄`() = runTest {
        val stale = GatedNetworkStreams()
        val fresh = GatedNetworkStreams()
        val recorder = EntryRecorder()
        var current = stale
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { emptyList() },
            streams = { current },
        )

        opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)
        runCurrent()
        // 第二次打开进场，它的探测先完成；第一次的准备随后才从阻塞调用里回来。
        current = fresh
        fresh.gate.complete(Unit)
        opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)
        runCurrent()
        assertEquals(listOf(networkVideo.location), fresh.probed)

        stale.gate.complete(Unit)
        runCurrent()

        assertEquals("作废的第一次打开丢自己的句柄", 1, stale.preparedDiscards)
        assertEquals("不能丢后一次刚探好的句柄", 0, fresh.preparedDiscards)
        assertEquals(listOf(networkVideo.location), fresh.probed)
    }

    @Test
    fun `音频准备完成后才进入播放页`() = runTest {
        val audio = testVideo("片头曲.mp3", "content://root/片头曲.mp3")
        val directoryGate = CompletableDeferred<Unit>()
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = {
                directoryGate.await()
                listOf(audio)
            },
            streams = { throw AssertionError("本地音频不应建立网络连接") },
        )

        val generation = opener.open(audio, listOf(audio), enterBeforePreparing = false)
        runCurrent()

        assertTrue("音频不预先进入播放页", recorder.events.isEmpty())
        assertEquals(EntryPhase.Preparing, entryState.phase)
        assertFalse("音频准备在浏览页完成，不显示准备期界面", entryState.preparingUi)

        directoryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("enter", "start:$generation"), recorder.events)
        assertEquals(listOf(audio), recorder.opened?.playlist)
        assertEquals(EntryPhase.AwaitingHandover, entryState.phase)
        assertFalse("音频进入时不显示准备期界面", entryState.preparingUi)
    }

    @Test
    fun `音频准备失败不进入播放页也不退出列表`() = runTest {
        val audio = testVideo("片头曲.mp3", "content://root/片头曲.mp3")
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { throw IllegalStateException("目录读不了") },
            streams = { throw AssertionError("本地音频不应建立网络连接") },
        )

        opener.open(audio, listOf(audio), enterBeforePreparing = false)
        advanceUntilIdle()

        assertEquals(0, recorder.enterCount)
        assertNull(recorder.opened)
        assertEquals(MediaFailure.Unknown, recorder.shownFailure)
        assertEquals(0, recorder.leaveCount)
        assertNull(entryState.phase)
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
        assertEquals(listOf("enter", "failure:Authentication", "leave"), recorder.events)
        assertNull(entryState.phase)
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
    fun `准备失败写入诊断日志`() = runTest {
        val localVideo = testVideo("本地.mkv", "content://root/本地.mkv")
        val sink = MemoryDiagnosticsSink()
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher(testScheduler)).apply { enabled = true }
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { throw IllegalStateException("目录读不了") },
            streams = { throw AssertionError("本地媒体不应建立网络连接") },
            diagnostics = diagnostics,
        )

        opener.open(localVideo, listOf(localVideo), enterBeforePreparing = true)
        advanceUntilIdle()

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("准备失败: 本地.mkv (Unknown)"))
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
    fun `取消打开后迟到的准备成功被丢弃`() = runTest {
        val streams = GatedNetworkStreams()
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = { emptyList() },
            streams = { streams },
        )

        val generation = opener.open(networkVideo, listOf(networkVideo), enterBeforePreparing = true)
        runCurrent()
        // 取消只清入口阶段，不打断阻塞中的准备：迟到的成功必须按代际丢弃。
        entryState.cancelEntry()
        streams.gate.complete(Unit)
        advanceUntilIdle()

        assertNull(recorder.opened)
        assertNull(recorder.shownFailure)
        assertEquals(0, recorder.leaveCount)
        assertFalse(entryState.isCurrent(generation))
    }

    @Test
    fun `取消打开后迟到的准备失败不提示也不离开`() = runTest {
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
        entryState.cancelEntry()
        streams.failure = MediaSourceException(MediaFailure.Unreachable, "连不上")
        streams.gate.complete(Unit)
        advanceUntilIdle()

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

    @Test
    fun `音频取消打开后迟到的准备成功不进入播放页`() = runTest {
        val audio = testVideo("片头曲.mp3", "content://root/片头曲.mp3")
        val directoryGate = CompletableDeferred<Unit>()
        val recorder = EntryRecorder()
        val opener = opener(
            scope = this,
            recorder = recorder,
            directoryEntries = {
                directoryGate.await()
                listOf(audio)
            },
            streams = { throw AssertionError("本地音频不应建立网络连接") },
        )

        val generation = opener.open(audio, listOf(audio), enterBeforePreparing = false)
        runCurrent()
        entryState.cancelEntry()
        directoryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, recorder.enterCount)
        assertNull(recorder.opened)
        assertNull(recorder.shownFailure)
        assertFalse(entryState.isCurrent(generation))
    }
}
