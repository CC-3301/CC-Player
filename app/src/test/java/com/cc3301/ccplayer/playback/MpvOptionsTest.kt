package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvOptionsTest {

    private val options = MpvOptions.initial(CACHE_DIR, FONTS_DIR, diagnosticsEnabled = false)

    @Test
    fun `hardware decoding falls back to software automatically`() {
        assertEquals("auto", options["hwdec"])
    }

    @Test
    fun `video output renders into the attached surface`() {
        assertEquals("gpu", options["vo"])
    }

    @Test
    fun `shader and icc caches live in the app cache directory`() {
        assertEquals(CACHE_DIR, options["gpu-shader-cache-dir"])
        assertEquals(CACHE_DIR, options["icc-cache-dir"])
    }

    @Test
    fun `mpv does not read a user config`() {
        assertEquals("no", options["config"])
    }

    @Test
    fun `the finished file stays open on its last frame`() {
        assertEquals("yes", options["keep-open"])
    }

    @Test
    fun `the native log bridge is configured for error level without terminal output`() {
        assertEquals("no", options["terminal"])
        assertEquals("all=error", options["msg-level"])
    }

    @Test
    fun `the audio buffer tolerates a blocking video output rebuild`() {
        assertEquals("0.5", options["audio-buffer"])
    }

    @Test
    fun `the embedded subtitle fonts are handed to mpv as the subtitle fonts directory`() {
        assertEquals(FONTS_DIR, options["sub-fonts-dir"])
    }

    @Test
    fun `diagnostics raise the native log level to warn`() {
        val diagnosticsOptions = MpvOptions.initial(CACHE_DIR, FONTS_DIR, diagnosticsEnabled = true)

        assertEquals("all=warn", diagnosticsOptions["msg-level"])
    }

    private companion object {
        const val CACHE_DIR = "/data/user/0/com.cc3301.ccplayer/cache"
        const val FONTS_DIR = "/data/user/0/com.cc3301.ccplayer/files/subtitle-fonts"
    }
}
