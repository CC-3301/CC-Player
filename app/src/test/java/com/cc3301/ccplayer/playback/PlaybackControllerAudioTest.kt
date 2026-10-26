package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音频体验：起播与跳转后的淡入请求，音频输出中断后的暂停。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerAudioTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()
    private val controller = PlaybackController(
        engine,
        progress,
        CoroutineScope(UnconfinedTestDispatcher()),
        nowMillis = { 0L },
    )

    @Test
    fun `fade in lasts the fixed 500 milliseconds`() {
        assertEquals(500L, PlaybackController.FADE_IN_MILLIS)
    }

    @Test
    fun `starting a media asks for a fade in`() {
        open(media)

        assertEquals(listOf(PlaybackController.FADE_IN_MILLIS), engine.fadeInDurations)
    }

    @Test
    fun `seeking asks for another fade in`() {
        open(media)
        engine.reportMediaLoaded()
        engine.reportPositionChanged(1_000, 60_000)
        controller.updateSeekPreview(30_000)

        controller.commitSeekPreview()

        assertEquals(listOf(500L, 500L), engine.fadeInDurations)
    }

    @Test
    fun `previewing a position without committing asks for no fade in`() {
        open(media)
        engine.reportMediaLoaded()

        controller.updateSeekPreview(30_000)

        assertEquals(listOf(PlaybackController.FADE_IN_MILLIS), engine.fadeInDurations)
    }

    @Test
    fun `audio output loss pauses the session`() {
        open(media)
        engine.reportMediaLoaded()

        controller.handleAudioOutputLost()

        assertEquals(1, engine.pauseCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `audio output loss never resumes playback by itself`() {
        open(media)
        engine.reportMediaLoaded()

        controller.handleAudioOutputLost()
        engine.reportPauseChanged(true)
        engine.reportPositionChanged(4_000, 90_000)

        assertEquals(0, engine.resumeCalls)
        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `a media loaded after audio output loss stays paused`() {
        open(media)

        controller.handleAudioOutputLost()
        engine.reportMediaLoaded()

        assertEquals(PlaybackState.Paused, controller.state.value)
    }

    @Test
    fun `audio output loss without an open media does nothing`() {
        controller.handleAudioOutputLost()

        assertEquals(0, engine.pauseCalls)
        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    @Test
    fun `the user can resume playback after audio output loss`() {
        open(media)
        engine.reportMediaLoaded()
        controller.handleAudioOutputLost()

        controller.resume()
        engine.reportPauseChanged(false)

        assertEquals(1, engine.resumeCalls)
        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    @Test
    fun `a new session starts without a pending pause`() {
        open(media)
        controller.handleAudioOutputLost()

        controller.stop()
        open(media)
        engine.reportMediaLoaded()

        assertEquals(PlaybackState.Playing, controller.state.value)
    }

    private fun open(vararg files: MediaFile) {
        controller.openPlaylist(files.toList(), files.first())
    }

    private companion object {
        val media = MediaFile(name = "episode1.mkv", location = "content://media/external/video/media/42")
    }
}
