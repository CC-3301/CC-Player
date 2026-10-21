package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.MemoryDiagnosticsSink
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerDiagnosticsTest {

    @Test
    fun `开启后记录加载与播放状态迁移`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher()).apply { enabled = true }
        val engine = FakePlaybackEngine()
        val media = MediaFile(name = "ep1.mkv", location = "smb://nas/share/ep1.mkv")
        val controller = PlaybackController(
            engine,
            FakePlaybackProgressRepository(),
            CoroutineScope(UnconfinedTestDispatcher()),
            diagnostics = diagnostics,
        )

        controller.openPlaylist(listOf(media), media)
        engine.reportMediaLoaded()

        val text = sink.lines.joinToString("\n")
        assertTrue(text.contains("加载开始: ep1.mkv"))
        assertTrue(text.contains("加载成功: ep1.mkv"))
        assertTrue(text.contains("播放状态: Idle -> Loading"))
        assertTrue(text.contains("播放状态: Loading -> Playing"))
    }
}
