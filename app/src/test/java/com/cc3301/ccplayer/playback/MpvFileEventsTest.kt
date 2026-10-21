package com.cc3301.ccplayer.playback

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun `a stop during an in-flight replacement swallows both pending ends`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        // 切集：旧文件被替换，目标仍在途。
        events.onStart()
        // 在途目标未结束时停止，然后立即重开原视频。
        events.onStop()
        events.onStart()

        // 旧文件与在途目标各产生一次 END_FILE，都不能当成失败。
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))
        assertTrue(events.onFileLoaded(isTarget = true))
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = true))
    }

    @Test
    fun `a FILE_LOADED arriving after stop is ignored`() {
        events.onStart()
        events.onStop()

        assertFalse(events.onFileLoaded(isTarget = true))
        assertFalse(events.isLoaded)
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile(atEndByPosition = false))

        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
    }

    @Test
    fun `concurrent start and event calls stay consistent`() {
        val pool = Executors.newFixedThreadPool(4)
        repeat(500) {
            pool.execute { events.onStart() }
            pool.execute { events.onFileLoaded(isTarget = true) }
            pool.execute { events.onEofChanged(true) }
            pool.execute { events.onEndFile(atEndByPosition = false) }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        events.reset()
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onEofChanged(true)
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile(atEndByPosition = false))
    }

    @Test
    fun `a ready processed before the seek event keeps the old serial`() {
        val signals = MpvReadySignals()
        signals.onStart()
        assertEquals(0, signals.acceptReady(needsFrameConfirmation = true))

        // seek 命令已下发，但旧的 PLAYBACK_RESTART 先于 MPV_EVENT_SEEK 被处理。
        val seekSerial = signals.onSeekIssued()
        assertEquals(1, seekSerial)
        assertEquals(0, signals.acceptReady(needsFrameConfirmation = false))

        // SEEK 事件到达后，后续 ready 才使用新序号。
        signals.onSeekEvent()
        assertEquals(1, signals.acceptReady(needsFrameConfirmation = false))
        assertEquals(1, signals.acceptReady(needsFrameConfirmation = true))
    }

    @Test
    fun `each seek event advances the accepted serial in order`() {
        val signals = MpvReadySignals()
        signals.onStart()

        assertEquals(1, signals.onSeekIssued())
        assertEquals(2, signals.onSeekIssued())

        signals.onSeekEvent()
        assertEquals(1, signals.acceptReady(needsFrameConfirmation = false))

        signals.onSeekEvent()
        assertEquals(2, signals.acceptReady(needsFrameConfirmation = true))
    }

    @Test
    fun `a repeated ready in the same seek serial is reported once`() {
        val signals = MpvReadySignals()
        signals.onStart()

        assertEquals(0, signals.acceptReady(needsFrameConfirmation = true))
        assertEquals(null, signals.acceptReady(needsFrameConfirmation = true))
        assertEquals(0, signals.acceptReady(needsFrameConfirmation = false))
        assertEquals(null, signals.acceptReady(needsFrameConfirmation = false))
    }

    @Test
    fun `a new load clears pending seeks and the accepted serial`() {
        val signals = MpvReadySignals()
        signals.onStart()
        assertEquals(1, signals.onSeekIssued())

        // 上一轮 seek 没等到 MPV_EVENT_SEEK 就开始了新加载。
        signals.onStart()
        assertEquals(0, signals.acceptReady(needsFrameConfirmation = false))

        assertEquals(1, signals.onSeekIssued())
        signals.onSeekEvent()
        assertEquals(1, signals.acceptReady(needsFrameConfirmation = true))
    }

    @Test
    fun `path comparison requires a present matching path`() {
        assertTrue(isTargetPath(target = "root/ep1.mkv", path = "root/ep1.mkv"))
        assertFalse(isTargetPath(target = null, path = "root/ep1.mkv"))
        assertFalse(isTargetPath(target = "root/ep1.mkv", path = null))
        assertFalse(isTargetPath(target = "root/ep1.mkv", path = "root/ep2.mkv"))
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

    @Test
    fun `FILE_LOADED is associated with the start that produced it`() {
        val queue = MpvLoadQueue()
        val first = queue.onStart("root/ep1.mkv")
        assertEquals(first, queue.takeFileLoaded("root/ep1.mkv"))

        val second = queue.onStart("root/ep1.mkv")
        val third = queue.onStart("root/ep1.mkv")

        // 同 URL 重载：事件按命令顺序对应。
        assertEquals(second, queue.takeFileLoaded("root/ep1.mkv"))
        assertEquals(third, queue.takeFileLoaded("root/ep1.mkv"))
    }

    @Test
    fun `a superseded in-flight load is skipped on its end event`() {
        val queue = MpvLoadQueue()
        val first = queue.onStart("root/ep1.mkv")
        val second = queue.onStart("root/ep2.mkv")

        assertEquals(first, queue.onEndFile(isReplaced = true))
        assertEquals(second, queue.takeFileLoaded("root/ep2.mkv"))
    }

    @Test
    fun `a same URL reload keeps the old event on the old generation`() {
        val queue = MpvLoadQueue()
        val old = queue.onStart("root/ep1.mkv")
        assertEquals(old, queue.takeFileLoaded("root/ep1.mkv"))

        val replacement = queue.onStart("root/ep1.mkv")
        assertEquals(old, queue.onEndFile(isReplaced = true))
        assertEquals(replacement, queue.takeFileLoaded("root/ep1.mkv"))
    }

    @Test
    fun `the end queue matches repeated replacements in command order`() {
        val queue = MpvLoadQueue()
        val first = queue.onStart("root/ep1.mkv")
        assertEquals(first, queue.takeFileLoaded("root/ep1.mkv"))

        val second = queue.onStart("root/ep2.mkv")
        val third = queue.onStart("root/ep3.mkv")

        assertEquals(first, queue.onEndFile(isReplaced = true))
        assertEquals(second, queue.onEndFile(isReplaced = true))
        assertEquals(third, queue.takeFileLoaded("root/ep3.mkv"))
    }

    @Test
    fun `a late FILE_LOADED from a natural end does not attach to the next load`() {
        val queue = MpvLoadQueue()
        val first = queue.onStart("root/ep1.mkv")
        assertEquals(first, queue.takeFileLoaded("root/ep1.mkv"))
        assertEquals(first, queue.onEndFile(isReplaced = false))

        val second = queue.onStart("root/ep1.mkv")
        assertEquals(second, queue.takeFileLoaded("root/ep1.mkv"))
    }

    @Test
    fun `stop drops in-flight loads and the stopped file's end`() {
        val queue = MpvLoadQueue()
        val first = queue.onStart("root/ep1.mkv")
        assertEquals(first, queue.takeFileLoaded("root/ep1.mkv"))
        val inFlight = queue.onStart("root/ep2.mkv")
        queue.onStop()

        // 被替换的旧文件与被停止的在途加载各来一次 END_FILE。
        assertEquals(first, queue.onEndFile(isReplaced = true))
        assertEquals(inFlight, queue.onEndFile(isReplaced = true))
        assertEquals(null, queue.takeFileLoaded("root/ep1.mkv"))
    }

    @Test
    fun `a late FILE_LOADED after stop does not take the new load's place`() {
        val queue = MpvLoadQueue()
        queue.onStart("root/ep1.mkv")
        queue.onStop()
        val restarted = queue.onStart("root/ep2.mkv")

        // 停止前旧文件的迟到 FILE_LOADED：路径不属于新队首，不能出队。
        assertEquals(
            null,
            queue.takeFileLoaded("root/ep1.mkv"),
        )
        assertEquals(restarted, queue.takeFileLoaded("root/ep2.mkv"))
    }

    @Test
    fun `a stale ready from an old generation does not take the current slot`() {
        val queue = MpvLoadQueue()
        val first = queue.onStart("root/ep1.mkv")
        assertEquals(first, queue.takeFileLoaded("root/ep1.mkv"))
        queue.onStart("root/ep2.mkv")
        val current = queue.onStart("root/ep3.mkv")
        // 被取代目标的迟到 FILE_LOADED 落到旧代际。
        assertEquals("root/ep2.mkv", queue.takeFileLoaded("root/ep2.mkv")?.url)
        val signals = MpvReadySignals()
        signals.onStart()

        // 旧代际 ready 先到：既不上报，也不占当前代际的去重槽。
        assertEquals(null, signals.acceptReadyFor(queue, needsFrameConfirmation = true))

        assertEquals(current, queue.takeFileLoaded("root/ep3.mkv"))
        val ready = signals.acceptReadyFor(queue, needsFrameConfirmation = true)
        assertEquals(current, ready?.spec)
        assertEquals(0, ready?.serial)
    }

    @Test
    fun `discarding a dead generation lets the restore load own the next FILE_LOADED`() {
        val queue = MpvLoadQueue()
        queue.onStart("root/ep2.mkv")
        queue.discardInFlight()

        val restored = queue.onStart("root/ep1.mkv")
        assertEquals(restored, queue.takeFileLoaded("root/ep1.mkv"))
        val signals = MpvReadySignals()
        signals.onStart()
        assertEquals(restored, signals.acceptReadyFor(queue, needsFrameConfirmation = false)?.spec)
    }
}
