package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎事件到“替换 / 自然结束 / 失败”的映射。
 */
class MpvFileEventsTest {

    private val events = MpvFileEvents()

    @Test
    fun `a clean session ends naturally at eof`() {
        events.onStart()
        assertTrue(events.onFileLoaded())
        events.onEofChanged(true)

        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `a file that ends before eof is a failure`() {
        events.onStart()
        assertTrue(events.onFileLoaded())

        assertEquals(MpvFileEvents.End.Failed, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `a missing eof property falls back to the end position`() {
        events.onStart()
        assertTrue(events.onFileLoaded())

        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = true))
    }

    @Test
    fun `the old file end caused by a replacement is not an end of playback`() {
        events.onStart()
        assertTrue(events.onFileLoaded())
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = true))
        assertTrue(events.onFileLoaded())
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = true))
    }

    @Test
    fun `a load that fails before FILE_LOADED is a failure`() {
        events.onStart()
        assertTrue(events.onFileLoaded())
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        assertEquals(MpvFileEvents.End.Failed, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `a stale FILE_LOADED for the replaced file is ignored`() {
        events.onStart()
        assertTrue(events.onFileLoaded())
        events.onStart()

        assertFalse(events.onFileLoaded())
        assertFalse(events.isLoaded)
    }

    @Test
    fun `eof updates from an older generation are ignored`() {
        events.onStart()
        assertTrue(events.onFileLoaded())
        events.onStart()
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        events.onEofChanged(true)

        assertEquals(MpvFileEvents.End.Failed, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `reset clears the replacement and end state`() {
        events.onStart()
        assertTrue(events.onFileLoaded())
        events.onStart()
        events.reset()

        assertFalse(events.isLoaded)
        assertEquals(MpvFileEvents.End.Failed, events.onEndFile(atEndByPosition = true))
    }
}
