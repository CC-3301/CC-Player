package com.cc3301.ccplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private val engine = FakePlaybackEngine()
    private val controller = PlaybackController(engine)

    @Test
    fun `open hands the url to the engine and reports loading`() {
        controller.open(MEDIA_URL)

        assertEquals(listOf(MEDIA_URL), engine.startedUrls)
        assertEquals(PlaybackState.Loading, controller.state.value)
    }

    @Test
    fun `opened media reports playing once the engine loaded it`() {
        controller.open(MEDIA_URL)

        engine.reportMediaLoaded()

        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `pause and resume are forwarded to the engine`() {
        controller.open(MEDIA_URL)
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

        controller.open(MEDIA_URL)
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
        controller.open(MEDIA_URL)
        engine.reportMediaLoaded()

        engine.reportPlaybackEnded()

        assertEquals(PlaybackState.Ended, controller.state.value)
        engine.reportPauseChanged(true)
        assertEquals(PlaybackState.Ended, controller.state.value)
    }

    @Test
    fun `stop clears the session and a following end callback keeps it idle`() {
        controller.open(MEDIA_URL)
        engine.reportMediaLoaded()

        controller.stop()

        assertEquals(1, engine.stopCalls)
        assertEquals(PlaybackState.Idle, controller.state.value)

        engine.reportPlaybackEnded()

        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    private companion object {
        const val MEDIA_URL = "content://media/external/video/media/42"
    }
}
