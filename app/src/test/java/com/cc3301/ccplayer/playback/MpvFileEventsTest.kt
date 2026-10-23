package com.cc3301.ccplayer.playback

import `is`.xyz.mpv.MPVLib
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎事件到“替换 / 失败”的映射，以及 eof-reached 作为唯一自然播完信号。
 */
class MpvFileEventsTest {

    private val events = MpvFileEvents()

    @Test
    fun `a clean session reports the natural end once at eof`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        assertTrue(events.onEofChanged(true))
        assertFalse(events.onEofChanged(true))
    }

    @Test
    fun `a file that ends before eof is a failure`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        assertEquals(MpvFileEvents.End.Failed, events.onEndFile())
    }

    @Test
    fun `a seek after eof allows the next natural end to report again`() {
        val coordinator = MpvLoadCoordinator()
        val spec = coordinator.start("root/ep1.mkv")
        assertEquals(spec, coordinator.onFileLoaded("root/ep1.mkv"))
        assertEquals(spec.token, coordinator.onEofChanged(true))
        assertEquals(null, coordinator.onEofChanged(true))

        // 拖回片尾外再播到结尾：同一代际要能再次上报播完。
        coordinator.onSeekIssued()
        assertEquals(spec.token, coordinator.onEofChanged(true))
    }

    @Test
    fun `an end after the reported eof settles the loaded file`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onEofChanged(true)

        assertEquals(MpvFileEvents.End.Ended, events.onEndFile())
        assertFalse(events.isLoaded)
    }

    @Test
    fun `an eof reset before an unload still settles as the natural end`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onEofChanged(true)
        // 卸载时属性可能先回到 false，但播完已经上报过。
        events.onEofChanged(false)

        assertEquals(MpvFileEvents.End.Ended, events.onEndFile())
    }

    @Test
    fun `the old file end caused by a replacement is not an end of playback`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        assertTrue(events.onFileLoaded(isTarget = true))
        assertTrue(events.onEofChanged(true))
    }

    @Test
    fun `a load that fails before FILE_LOADED is a failure`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        assertEquals(MpvFileEvents.End.Failed, events.onEndFile())
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
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        assertFalse(events.onEofChanged(true))

        assertEquals(MpvFileEvents.End.Failed, events.onEndFile())
    }

    @Test
    fun `eof after an explicit stop is ignored`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStop()

        assertFalse(events.onEofChanged(true))
    }

    @Test
    fun `cancelling while the target is still loading swallows the aborted target end`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        // 切集开始：旧文件会被替换。
        events.onStart()
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        // 目标还在加载时取消：恢复原视频的 loadfile 取代了在途目标。
        events.onStart()
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        // 被中止的目标之后，恢复文件正常打开并自然播完。
        assertTrue(events.onFileLoaded(isTarget = true))
        assertTrue(events.onEofChanged(true))
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile())
    }

    @Test
    fun `an old end arriving late is swallowed by the pending counter`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))

        events.onStart()
        // 恢复命令先到，旧文件与在途目标的结束事件都还没到。
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        assertTrue(events.onFileLoaded(isTarget = true))
        assertTrue(events.onEofChanged(true))
    }

    @Test
    fun `a stop end is swallowed and the next file loads cleanly`() {
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        events.onStop()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        assertTrue(events.onEofChanged(true))
    }

    @Test
    fun `a stop followed immediately by a start swallows the stop end`() {
        events.onStart()
        events.onStop()
        events.onStart()

        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
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
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())
        assertTrue(events.onFileLoaded(isTarget = true))
        assertTrue(events.onEofChanged(true))
    }

    @Test
    fun `a FILE_LOADED arriving after stop is ignored`() {
        events.onStart()
        events.onStop()

        assertFalse(events.onFileLoaded(isTarget = true))
        assertFalse(events.isLoaded)
        assertEquals(MpvFileEvents.End.Replaced, events.onEndFile())

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
            pool.execute { events.onEndFile() }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        events.reset()
        events.onStart()
        assertTrue(events.onFileLoaded(isTarget = true))
        assertTrue(events.onEofChanged(true))
        assertEquals(MpvFileEvents.End.Ended, events.onEndFile())
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
        assertEquals(MpvFileEvents.End.Failed, events.onEndFile())
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
        val loads = MpvLoadQueue()
        val first = loads.onStart("root/ep1.mkv")
        assertEquals(first, loads.takeFileLoaded("root/ep1.mkv"))
        loads.onStart("root/ep2.mkv")
        val current = loads.onStart("root/ep3.mkv")
        // 被取代目标的迟到 FILE_LOADED 落到旧代际。
        assertEquals("root/ep2.mkv", loads.takeFileLoaded("root/ep2.mkv")?.url)
        val coordinator = MpvLoadCoordinator(loads = loads)

        // 旧代际 ready 先到：既不上报，也不占当前代际的去重槽。
        assertEquals(null, coordinator.acceptReady(needsFrameConfirmation = true))

        assertEquals(current, loads.takeFileLoaded("root/ep3.mkv"))
        val ready = coordinator.acceptReady(needsFrameConfirmation = true)
        assertEquals(current, ready?.spec)
        assertEquals(0, ready?.serial)
    }

    @Test
    fun `discarding a dead generation lets the restore load own the next FILE_LOADED`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep2.mkv")
        coordinator.discardInFlight()

        val restored = coordinator.start("root/ep1.mkv")
        // 死代际迟到的 END_FILE 先到：被丢弃，不伤恢复加载；结束计数已收尾。
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile())
        assertEquals(restored, coordinator.onFileLoaded("root/ep1.mkv"))
        assertEquals(restored, coordinator.acceptReady(needsFrameConfirmation = false)?.spec)
        // 恢复文件自己的自然结束由 eof-reached 上报一次。
        assertEquals(restored.token, coordinator.onEofChanged(true))
    }

    @Test
    fun `an end after eof settles the loaded generation`() {
        val coordinator = MpvLoadCoordinator()
        val spec = coordinator.start("root/ep1.mkv")
        assertEquals(spec, coordinator.onFileLoaded("root/ep1.mkv"))
        assertEquals(spec.token, coordinator.onEofChanged(true))

        assertEquals(MpvFileEvents.End.Ended, coordinator.onEndFile())
        assertEquals(null, coordinator.currentLoadedToken())
        assertEquals(null, coordinator.acceptReady(needsFrameConfirmation = false))
    }

    @Test
    fun `a keep-open natural end reports eof without an END_FILE`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        assertEquals("root/ep1.mkv", coordinator.onFileLoaded("root/ep1.mkv")?.url)

        assertEquals(1, coordinator.onEofChanged(true))
        // 自然播完本身没有 END_FILE；下一次 loadfile 仍会替换旧文件并产生一次结束事件。
        val next = coordinator.start("root/ep2.mkv")
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile())
        assertEquals(next, coordinator.onFileLoaded("root/ep2.mkv"))
        assertEquals(next, coordinator.acceptReady(needsFrameConfirmation = false)?.spec)
    }

    @Test
    fun `a late FILE_LOADED for a stopped same URL load is rejected by the end count`() {
        val coordinator = MpvLoadCoordinator()
        val first = coordinator.start("root/ep1.mkv")
        assertEquals(first, coordinator.onFileLoaded("root/ep1.mkv"))
        coordinator.stop()
        val replacement = coordinator.start("root/ep1.mkv")

        // 同 URL：path 校验无法区分，靠 stop 留下的待吞结束事件拒绝旧 FILE_LOADED。
        assertEquals(null, coordinator.onFileLoaded("root/ep1.mkv"))
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile())
        assertEquals(replacement, coordinator.onFileLoaded("root/ep1.mkv"))
    }

    @Test
    fun `a late FILE_LOADED for a stopped in-flight same URL load is rejected`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.stop()
        val replacement = coordinator.start("root/ep1.mkv")

        assertEquals(null, coordinator.onFileLoaded("root/ep1.mkv"))
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile())
        assertEquals(replacement, coordinator.onFileLoaded("root/ep1.mkv"))
    }

    @Test
    fun `the same critical section rejects a stale ready without consuming the slot`() {
        val loads = MpvLoadQueue()
        loads.onStart("root/ep1.mkv")
        loads.takeFileLoaded("root/ep1.mkv")
        loads.onStart("root/ep2.mkv")
        val current = loads.onStart("root/ep3.mkv")
        val coordinator = MpvLoadCoordinator(loads = loads)

        // 旧代际的 FILE_LOADED 先到：当前代际还没加载完成，ready 不占槽。
        loads.takeFileLoaded("root/ep2.mkv")
        assertEquals(null, coordinator.acceptReady(needsFrameConfirmation = false))

        assertEquals(current, loads.takeFileLoaded("root/ep3.mkv"))
        assertEquals(current, coordinator.acceptReady(needsFrameConfirmation = false)?.spec)
    }

    @Test
    fun `the first position after a start only rebuilds the baseline`() {
        val gate = PositionProgressGate()
        gate.onStart(0)

        assertFalse(gate.onPosition(0))
        assertTrue(gate.onPosition(40))
    }

    @Test
    fun `a queued old position after a backward seek is not progress`() {
        val gate = PositionProgressGate()
        gate.onStart(0)
        assertFalse(gate.onPosition(1_000))

        gate.onSeek(200)

        // 队列里较大的旧位置先到：只能重建基线，不能当作出帧证据。
        assertFalse(gate.onPosition(1_000))
        assertFalse(gate.onPosition(250))
        assertTrue(gate.onPosition(260))
    }

    @Test
    fun `a jump larger than two seconds only rebuilds the baseline`() {
        val gate = PositionProgressGate()
        gate.onStart(0)
        assertFalse(gate.onPosition(0))

        // 超过 2000ms 的跳变不能算前进，只重建基线。
        assertFalse(gate.onPosition(5_000))
        assertTrue(gate.onPosition(5_040))
    }

    @Test
    fun `mpv error logs keep the root cause and the latest lines`() {
        val collector = MpvErrorCollector()

        collector.onLog("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO, "普通信息")
        collector.onLog("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "打开文件失败")
        collector.onLog("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "  ")
        collector.onLog("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN, "警告不算错误")
        collector.onLog("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "解码失败")

        assertEquals("cplayer: 打开文件失败 | ffmpeg: 解码失败", collector.lastErrorText())

        collector.reset()
        assertEquals(null, collector.lastErrorText())
    }

    @Test
    fun `mpv error logs keep the first error and the latest capacity`() {
        val collector = MpvErrorCollector(capacity = 2)
        collector.onLog("a", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "1")
        collector.onLog("a", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "2")
        collector.onLog("a", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "3")

        // 首条根因不能被后续错误挤掉。
        assertEquals("a: 1 | a: 3", collector.lastErrorText())
    }

    @Test
    fun `a new load clears the previous error log before END_FILE reads it`() {
        val failures = mutableListOf<String?>()
        val bridge = MpvErrorLogBridge { failures += it }

        // 引擎 start 换代，START_FILE 清空旧文件排队日志，随后才是本轮日志。
        bridge.start()
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "上一轮错误")
        bridge.onStartFile()
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "本轮错误")

        bridge.onEndFile(MpvFileEvents.End.Failed)
        assertEquals(listOf<String?>("ffmpeg: 本轮错误"), failures)

        // 读取后 reset：下一次 END_FILE 不再带出同一文本。
        bridge.onEndFile(MpvFileEvents.End.Failed)
        assertEquals(listOf<String?>("ffmpeg: 本轮错误", null), failures)
    }

    @Test
    fun `start clears the previous load even without a START_FILE event`() {
        val failures = mutableListOf<String?>()
        val bridge = MpvErrorLogBridge { failures += it }

        bridge.start()
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "上一轮错误")
        bridge.start()
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "本轮错误")

        bridge.onEndFile(MpvFileEvents.End.Failed)
        assertEquals(listOf<String?>("ffmpeg: 本轮错误"), failures)
    }

    @Test
    fun `an END_FILE failure forwards the collected error text`() {
        val collector = MpvErrorCollector()
        collector.onLog("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "打开文件失败")

        var received: String? = "unchanged"
        dispatchPlaybackFailure(MpvFileEvents.End.Failed, collector::lastErrorText) { received = it }
        assertEquals("cplayer: 打开文件失败", received)

        received = "unchanged"
        dispatchPlaybackFailure(MpvFileEvents.End.Ended, collector::lastErrorText) { received = it }
        assertEquals("unchanged", received)
    }
}
