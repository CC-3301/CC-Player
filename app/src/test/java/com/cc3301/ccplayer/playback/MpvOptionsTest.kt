package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvOptionsTest {

    private val options = MpvOptions.initial(CACHE_DIR)

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

    private companion object {
        const val CACHE_DIR = "/data/user/0/com.cc3301.ccplayer/cache"
    }
}
