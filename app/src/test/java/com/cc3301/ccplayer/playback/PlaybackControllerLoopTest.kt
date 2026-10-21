package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播完当前的下一步：三种循环模式 × 自动连播开关两种取值。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerLoopTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()
    private val controller = PlaybackController(engine, progress, CoroutineScope(UnconfinedTestDispatcher()))

    @Test
    fun `auto play next starts on and the loop mode starts at sequential`() {
        assertTrue(controller.autoPlayNext.value)
        assertEquals(LoopMode.Sequential, controller.loopMode.value)
    }

    @Test
    fun `playback end plays the next episode in sequential mode`() {
        open("ep1.mkv", "ep2.mkv", "ep3.mkv", current = "ep1.mkv")

        endCurrentEpisode()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `auto advance keeps the origin progress until the next episode is ready`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        engine.reportMediaLoaded()
        engine.reportPositionChanged(30_000, 60_000)

        endCurrentEpisode()

        // 自动连播与手动切集一样，目标就绪前进度仍停在原视频上。
        assertEquals(PlaybackProgress(30_000, 60_000), controller.progress.value)

        engine.reportFileLoaded()
        engine.reportFirstFrameReady()

        assertEquals(0L, controller.progress.value.positionMillis)
        assertEquals(PlaybackState.Playing, controller.state.value)
        assertEquals(1, controller.currentIndex.value)
    }

    @Test
    fun `playback end at the end of the playlist stops in sequential mode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep2.mkv")

        endCurrentEpisode()

        assertEquals(listOf("root/ep2.mkv"), engine.startedUrls)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `playback end replays the current episode in single loop mode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.setLoopMode(LoopMode.SingleLoop)

        endCurrentEpisode()

        assertEquals(listOf("root/ep1.mkv", "root/ep1.mkv"), engine.startedUrls)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `playback end plays the next episode in list loop mode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.setLoopMode(LoopMode.ListLoop)

        endCurrentEpisode()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `playback end at the end of the playlist wraps in list loop mode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep2.mkv")
        controller.setLoopMode(LoopMode.ListLoop)

        endCurrentEpisode()

        assertEquals(listOf("root/ep2.mkv", "root/ep1.mkv"), engine.startedUrls)
        assertEquals(0, controller.currentIndex.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `auto play next off stops in sequential mode even mid playlist`() {
        open("ep1.mkv", "ep2.mkv", "ep3.mkv", current = "ep1.mkv")
        controller.setAutoPlayNext(false)

        endCurrentEpisode()

        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `auto play next off stops at the end of the playlist in sequential mode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep2.mkv")
        controller.setAutoPlayNext(false)

        endCurrentEpisode()

        assertEquals(listOf("root/ep2.mkv"), engine.startedUrls)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `auto play next off stops at the end of the playlist in list loop mode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep2.mkv")
        controller.setLoopMode(LoopMode.ListLoop)
        controller.setAutoPlayNext(false)

        endCurrentEpisode()

        assertEquals(listOf("root/ep2.mkv"), engine.startedUrls)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `auto play next off leaves single loop mode replaying`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.setLoopMode(LoopMode.SingleLoop)
        controller.setAutoPlayNext(false)

        endCurrentEpisode()

        assertEquals(listOf("root/ep1.mkv", "root/ep1.mkv"), engine.startedUrls)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `auto play next off does not block the manual next episode`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.setAutoPlayNext(false)

        controller.next()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `auto advance follows the natural name order of the playlist`() {
        val byModifiedTime = listOf(
            "ep10.mkv" to 300L,
            "ep2.mkv" to 200L,
            "ep1.mkv" to 100L,
        )
        controller.openPlaylist(
            byModifiedTime.map { (name, modified) -> mediaFile(name, modified) },
            mediaFile("ep1.mkv", 100),
        )

        endCurrentEpisode()
        engine.reportMediaLoaded()
        endCurrentEpisode()
        engine.reportMediaLoaded()
        endCurrentEpisode()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv", "root/ep10.mkv"), engine.startedUrls)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `the manual next episode keeps the natural name order after an auto advance`() {
        val byModifiedTime = listOf(
            "ep10.mkv" to 300L,
            "ep2.mkv" to 200L,
            "ep1.mkv" to 100L,
        )
        controller.openPlaylist(
            byModifiedTime.map { (name, modified) -> mediaFile(name, modified) },
            mediaFile("ep1.mkv", 100),
        )

        endCurrentEpisode()
        controller.next()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv", "root/ep10.mkv"), engine.startedUrls)
    }

    @Test
    fun `a stopped session does not restart when the engine reports the end`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.setLoopMode(LoopMode.SingleLoop)

        controller.stop()
        engine.reportPlaybackEnded()

        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    @Test
    fun `switching the loop mode walks through the three modes`() {
        controller.cycleLoopMode()
        assertEquals(LoopMode.ListLoop, controller.loopMode.value)

        controller.cycleLoopMode()
        assertEquals(LoopMode.SingleLoop, controller.loopMode.value)

        controller.cycleLoopMode()
        assertEquals(LoopMode.Sequential, controller.loopMode.value)
    }

    @Test
    fun `the loop mode does not outlive the session`() {
        open("ep1.mkv", "ep2.mkv", current = "ep1.mkv")
        controller.setLoopMode(LoopMode.SingleLoop)

        controller.stop()

        assertEquals(LoopMode.Sequential, controller.loopMode.value)
    }

    private fun open(vararg names: String, current: String) {
        controller.openPlaylist(names.map(::mediaFile), mediaFile(current))
    }

    private fun endCurrentEpisode() {
        engine.reportMediaLoaded()
        engine.reportPlaybackEnded()
    }

    private fun mediaFile(name: String, lastModified: Long = 0): MediaFile =
        MediaFile(name = name, location = "root/$name", lastModified = lastModified)
}
