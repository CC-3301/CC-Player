package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎事件到“替换 / 自然结束 / 失败”的映射，以及被中止的在途加载。
 */
class MpvFileEventsTest {

    private val events = MpvFileEvents()

    @Test
    fun `a clean session ends naturally at eof`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onEofChanged(true)

        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `a file that ends before eof is a failure`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        assertEquals(MpvFileEvents.End.Failed, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `a missing eof property falls back to the end position`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = true))
    }

    @Test
    fun `the old file end caused by a replacement is not an end of playback`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = true))
        assertTrue(events.onFileLoaded(isTarget = true))
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = true))
    }

    @Test
    fun `a load that fails before FILE_LOADED is a failure`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        assertEquals(MpvFileEvents.End.Failed, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `a stale FILE_LOADED for another file is ignored`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStart()

        assertFalse(events.onFileLoaded(isTarget = false))
        assertFalse(events.isLoaded)
    }

    @Test
    fun `eof updates from an older generation are ignored`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStart()
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        events.onEofChanged(true)

        assertEquals(MpvFileEvents.End.Failed, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `cancelling while the target is still loading swallows the aborted target end`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        // 切集开始：旧文件会被替换。
        events.onStart()
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        // 目标还在加载时取消：恢复原视频的 loadfile 取代了在途目标。
        events.onStart()
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        // 被中止的目标之后，恢复文件正常打开并结束。
        assertTrue(events.onFileLoaded(isTarget = true))
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = true))
    }

    @Test
    fun `an old end arriving late is swallowed by the pending counter`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        events.onStart()
        // 恢复命令先到，旧文件与在途目标的结束事件都还没到。
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = true))
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = true))
        assertTrue(events.onFileLoaded(isTarget = true))
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = true))
    }

    @Test
    fun `a stop end is swallowed and the next file loads cleanly`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStop()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = true))
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = true))
    }

    @Test
    fun `a stop followed immediately by a start swallows the stop end`() {
        events.onStart()
        events.onStop()
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        assertTrue(events.onFileLoaded(isTarget = true))
    }

    @Test
    fun `reset clears the replacement and end state`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStart()
        events.reset()

        assertFalse(events.isLoaded)
        assertEquals(MpvFileEvents.End.Failed, events.onEndFile(atEndByPosition = true))
    }
}
