package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.progress.FakePlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerVideoSizeTest {

    private val engine = FakePlaybackEngine()
    private val progress = FakePlaybackProgressRepository()
    private val controller = PlaybackController(engine, progress, CoroutineScope(UnconfinedTestDispatcher()))

    @Test
    fun `no reported video size leaves the aspect ratio unknown`() {
        open("ep1.mkv")

        assertNull(controller.videoAspectRatio.value)
    }

    @Test
    fun `a reported landscape video gives an aspect ratio above one`() {
        open("ep1.mkv")

        engine.reportVideoSizeChanged(1920, 1080)

        assertEquals(1920.0 / 1080.0, controller.videoAspectRatio.value!!, TOLERANCE)
    }

    @Test
    fun `a reported portrait video gives an aspect ratio below one`() {
        open("ep1.mkv")

        engine.reportVideoSizeChanged(1080, 1920)

        assertEquals(1080.0 / 1920.0, controller.videoAspectRatio.value!!, TOLERANCE)
    }

    @Test
    fun `a changed video size updates the aspect ratio`() {
        open("ep1.mkv")
        engine.reportVideoSizeChanged(1920, 1080)

        engine.reportVideoSizeChanged(1080, 1920)

        assertEquals(0.5625, controller.videoAspectRatio.value!!, TOLERANCE)
    }

    @Test
    fun `a non positive size does not overwrite a known aspect ratio`() {
        open("ep1.mkv")
        engine.reportVideoSizeChanged(1920, 1080)

        engine.reportVideoSizeChanged(0, 0)

        assertEquals(1920.0 / 1080.0, controller.videoAspectRatio.value!!, TOLERANCE)
    }

    @Test
    fun `a non positive size before any report leaves the aspect ratio unknown`() {
        open("ep1.mkv")

        engine.reportVideoSizeChanged(0, 1080)

        assertNull(controller.videoAspectRatio.value)
    }

    @Test
    fun `switching media keeps the last aspect ratio until the new size arrives`() {
        open("ep1.mkv", "ep2.mkv")
        engine.reportVideoSizeChanged(1920, 1080)

        controller.next()

        assertEquals(1920.0 / 1080.0, controller.videoAspectRatio.value!!, TOLERANCE)
    }

    @Test
    fun `the new media reports its own aspect ratio`() {
        open("ep1.mkv", "ep2.mkv")
        engine.reportVideoSizeChanged(1920, 1080)

        controller.next()
        engine.reportVideoSizeChanged(1080, 1920)

        assertEquals(0.5625, controller.videoAspectRatio.value!!, TOLERANCE)
    }

    @Test
    fun `stopping the session drops the aspect ratio`() {
        open("ep1.mkv")
        engine.reportVideoSizeChanged(1920, 1080)

        controller.stop()

        assertNull(controller.videoAspectRatio.value)
    }

    @Test
    fun `size reports after the session stopped are ignored`() {
        open("ep1.mkv")
        controller.stop()

        engine.reportVideoSizeChanged(1920, 1080)

        assertNull(controller.videoAspectRatio.value)
    }

    private fun open(vararg names: String) {
        val files = names.map(::mediaFile)
        controller.openPlaylist(files, files.first())
    }

    private fun mediaFile(name: String): MediaFile = MediaFile(name = name, location = "root/$name")

    private companion object {
        const val TOLERANCE = 0.0001
    }
}
