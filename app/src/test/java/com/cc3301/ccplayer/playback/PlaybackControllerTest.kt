package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private val engine = FakePlaybackEngine()
    private val controller = PlaybackController(engine)

    @Test
    fun `opening a media hands its location to the engine and reports loading`() {
        open(media)

        assertEquals(listOf(media.location), engine.startedUrls)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `opened media reports playing once the engine loaded it`() {
        open(media)

        engine.reportMediaLoaded()

        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `pause and resume are forwarded to the engine`() {
        open(media)
        engine.reportMediaLoaded()

        controller.pause()
        controller.resume()
        controller.togglePlayPause()

        assertEquals(2, engine.pauseCalls)
        assertEquals(1, engine.resumeCalls)
    }

    @Test
    fun `pause and resume before any media are ignored`() {
        controller.pause()
        controller.resume()

        assertEquals(0, engine.pauseCalls)
        assertEquals(0, engine.resumeCalls)
    }

    @Test
    fun `reported state follows the engine callback sequence`() = runTest {
        val states = mutableListOf<PlaybackState>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { states += it }
        }

        open(media)
        engine.reportMediaLoaded()
        controller.pause()
        engine.reportPauseChanged(true)
        controller.resume()
        engine.reportPauseChanged(false)
        engine.reportPlaybackEnded()
        collecting.cancel()

        assertEquals(
            listOf(
                PlaybackState.Idle,
                PlaybackState.Loading,
                PlaybackState.Playing,
                PlaybackState.Paused,
                PlaybackState.Playing,
                PlaybackState.Ended,
            ),
            states,
        )
    }

    @Test
    fun `playback end reports ended and later engine callbacks are ignored`() {
        open(media)
        engine.reportMediaLoaded()

        engine.reportPlaybackEnded()

        assertEquals(PlaybackState.Ended, controller.state.value)
        engine.reportPauseChanged(true)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `stop clears the session and a following end callback keeps it idle`() {
        open(media)
        engine.reportMediaLoaded()

        controller.stop()

        assertEquals(1, engine.stopCalls)
        assertEquals(PlaybackState.Idle, controller.state.value)

        engine.reportPlaybackEnded()

        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    @Test
    fun `playlist holds only the media files, sorted by natural name`() {
        val directory = MediaFile(name = "剧集", location = "root/剧集", isDirectory = true)

        open(mediaFile("ep10.mkv"), directory, mediaFile("ep2.mkv"), mediaFile("ep1.mkv"))

        assertEquals(listOf("ep1.mkv", "ep2.mkv", "ep10.mkv"), controller.playlist.value.map { it.name })
    }

    @Test
    fun `next episode is the adjacent file in natural name order`() {
        val files = listOf(mediaFile("ep10.mkv"), mediaFile("ep1.mkv"), mediaFile("ep2.mkv"))
        controller.openPlaylist(files, mediaFile("ep1.mkv"))

        controller.next()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `previous episode is the adjacent file in natural name order`() {
        val files = listOf(mediaFile("ep2.mkv"), mediaFile("ep10.mkv"), mediaFile("ep1.mkv"))
        controller.openPlaylist(files, mediaFile("ep2.mkv"))

        controller.previous()

        assertEquals(listOf("root/ep2.mkv", "root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `next episode ignores the order the view happens to show`() {
        val byModifiedTime = listOf(
            mediaFile("ep10.mkv", lastModified = 300),
            mediaFile("ep2.mkv", lastModified = 200),
            mediaFile("ep1.mkv", lastModified = 100),
        )
        controller.openPlaylist(byModifiedTime, mediaFile("ep1.mkv"))

        controller.next()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `next episode at the end of the playlist does nothing`() {
        val files = listOf(mediaFile("ep1.mkv"), mediaFile("ep2.mkv"))
        controller.openPlaylist(files, mediaFile("ep2.mkv"))

        controller.next()

        assertEquals(listOf("root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `previous episode at the start of the playlist does nothing`() {
        val files = listOf(mediaFile("ep1.mkv"), mediaFile("ep2.mkv"))
        controller.openPlaylist(files, mediaFile("ep1.mkv"))

        controller.previous()

        assertEquals(listOf("root/ep1.mkv"), engine.startedUrls)
    }

    @Test
    fun `next episode still works after the current media ended`() {
        val files = listOf(mediaFile("ep1.mkv"), mediaFile("ep2.mkv"))
        controller.openPlaylist(files, mediaFile("ep1.mkv"))
        engine.reportPlaybackEnded()

        controller.next()

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
    }

    @Test
    fun `selecting a playlist entry plays that file and reports loading`() {
        val files = listOf(mediaFile("ep1.mkv"), mediaFile("ep2.mkv"))
        controller.openPlaylist(files, mediaFile("ep1.mkv"))

        controller.playAt(1)

        assertEquals(listOf("root/ep1.mkv", "root/ep2.mkv"), engine.startedUrls)
        assertEquals(1, controller.currentIndex.value)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `dragging the progress bar previews a position without moving playback`() {
        open(media)
        engine.reportMediaLoaded()
        engine.reportPositionChanged(1_000, 60_000)

        controller.updateSeekPreview(30_000)

        assertEquals(30_000L, controller.seekPreviewMillis.value)
        assertEquals(emptyList<Long>(), engine.seekPositions)
        assertEquals(1_000L, controller.progress.value.positionMillis)
    }

    @Test
    fun `releasing the progress bar seeks to the previewed position`() {
        open(media)
        engine.reportMediaLoaded()
        controller.updateSeekPreview(30_000)

        controller.commitSeekPreview()

        assertEquals(listOf(30_000L), engine.seekPositions)
        assertNull(controller.seekPreviewMillis.value)
    }

    @Test
    fun `releasing without a preview does not seek`() {
        open(media)
        engine.reportMediaLoaded()

        controller.commitSeekPreview()

        assertEquals(emptyList<Long>(), engine.seekPositions)
    }

    @Test
    fun `engine position reports become the progress state`() {
        open(media)
        engine.reportMediaLoaded()

        engine.reportPositionChanged(4_000, 90_000)

        assertEquals(PlaybackProgress(4_000, 90_000), controller.progress.value)
    }

    @Test
    fun `decode mode starts at hardware decoding`() {
        assertEquals(DecodeMode.Hardware, controller.decodeMode.value)
    }

    @Test
    fun `switching the decode mode applies to the engine of this session`() {
        open(media)

        controller.toggleDecodeMode()

        assertEquals(DecodeMode.Software, controller.decodeMode.value)
        assertEquals(listOf(DecodeMode.Software), engine.decodeModes)
    }

    @Test
    fun `the decode mode override does not outlive the session`() {
        open(media)
        controller.setDecodeMode(DecodeMode.Software)

        controller.stop()

        assertEquals(DecodeMode.Hardware, controller.decodeMode.value)
    }

    private fun open(vararg files: MediaFile) {
        controller.openPlaylist(files.toList(), files.first())
    }

    private fun mediaFile(name: String, lastModified: Long = 0): MediaFile =
        MediaFile(name = name, location = "root/$name", lastModified = lastModified)

    private companion object {
        val media = MediaFile(name = "episode1.mkv", location = "content://media/external/video/media/42")
    }
}
