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
        coordinator.onSeekStarted()
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
        val seekSerial = signals.reserve()
        assertEquals(1, seekSerial)
        signals.onSeekIssued(seekSerial)
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

        val first = signals.reserve()
        val second = signals.reserve()
        assertEquals(1, first)
        assertEquals(2, second)
        signals.onSeekIssued(first)
        signals.onSeekIssued(second)

        signals.onSeekEvent()
        assertEquals(1, signals.acceptReady(needsFrameConfirmation = false))

        signals.onSeekEvent()
        assertEquals(2, signals.acceptReady(needsFrameConfirmation = true))
    }

    @Test
    fun `an extra seek event after a reissue does not move the accepted serial`() {
        val signals = MpvReadySignals()
        signals.onStart()

        val replaced = signals.reserve()
        val reissued = signals.reserve()
        signals.onSeekIssued(replaced)
        signals.onSeekReissued(replaced, reissued)

        // 被替换的那条命令也来了一条 SEEK 事件：消费指针停在新序号，不再被推走。
        signals.onSeekEvent()
        signals.onSeekEvent()
        assertEquals(reissued, signals.acceptReady(needsFrameConfirmation = false))
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
    fun `a refresh serial is consumed by its own seek event`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.onFileLoaded("root/ep1.mkv")

        val refresh = coordinator.reserveSeekSerial()
        coordinator.onSeekIssued(refresh)

        assertEquals(refresh, coordinator.onSeekEvent()?.serial)
        assertEquals(refresh, coordinator.acceptedSeekSerial())
    }

    @Test
    fun `refresh and real seek serials are consumed in command order`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.onFileLoaded("root/ep1.mkv")

        val refresh = coordinator.reserveSeekSerial()
        coordinator.onSeekIssued(refresh)
        val real = coordinator.reserveSeekSerial()
        coordinator.onSeekIssued(real)

        val refreshSeek = MpvRefreshSeek()
        refreshSeek.begin(refresh)
        // 刷新自己的 SEEK 只归刷新；真实 seek 的 SEEK 照常推进 accepted 序号。
        assertTrue(refreshSeek.onSeekConsumed(coordinator.onSeekEvent()?.serial))
        assertFalse(refreshSeek.onSeekConsumed(coordinator.onSeekEvent()?.serial))
        assertEquals(real, coordinator.acceptedSeekSerial())
    }

    @Test
    fun `a tombstoned refresh serial consumes a late seek without moving accepted`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.onFileLoaded("root/ep1.mkv")

        val refresh = coordinator.reserveSeekSerial()
        coordinator.onSeekIssued(refresh)
        coordinator.tombstoneSeekSerial(refresh)
        val real = coordinator.reserveSeekSerial()
        coordinator.onSeekIssued(real)

        // 迟到的刷新 SEEK 只消费坟标，不改 ready 的 accepted 序号。
        assertTrue(coordinator.onSeekEvent()?.tombstone == true)
        assertEquals(0, coordinator.acceptedSeekSerial())

        assertEquals(real, coordinator.onSeekEvent()?.serial)
        assertEquals(real, coordinator.acceptedSeekSerial())
    }

    @Test
    fun `discarding an unseen refresh serial keeps later seeks aligned`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.onFileLoaded("root/ep1.mkv")

        val refresh = coordinator.reserveSeekSerial()
        coordinator.onSeekIssued(refresh)
        // 刷新没有等到 SEEK：丢弃它的序号，后面真实 seek 的序号仍按顺序对齐。
        coordinator.discardSeekSerial(refresh)

        val real = coordinator.reserveSeekSerial()
        coordinator.onSeekIssued(real)
        assertEquals(real, coordinator.onSeekEvent()?.serial)
        assertEquals(real, coordinator.acceptedSeekSerial())
    }

    @Test
    fun `a new load clears pending seeks and the accepted serial`() {
        val signals = MpvReadySignals()
        signals.onStart()
        signals.onSeekIssued(signals.reserve())

        // 上一轮 seek 没等到 MPV_EVENT_SEEK 就开始了新加载。
        signals.onStart()
        assertEquals(0, signals.acceptReady(needsFrameConfirmation = false))

        val serial = signals.reserve()
        assertEquals(1, serial)
        signals.onSeekIssued(serial)
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
    fun `a reissued seek replaces its registration so a single seek event still lands`() {
        val coordinator = MpvLoadCoordinator()
        val spec = coordinator.start("root/ep1.mkv")
        assertEquals(spec, coordinator.onFileLoaded("root/ep1.mkv"))

        val replaced = coordinator.reserveSeekSerial()
        val reissued = coordinator.reserveSeekSerial()
        coordinator.onSeekIssued(replaced)
        // 改写后的再次下发：被替换的那条命令可能不再产生 SEEK 事件。
        coordinator.onSeekReissued(replaced, reissued)

        // 只来一条 SEEK 事件：序号推进到新的一跳，落定对得上，不用等超时。
        coordinator.onSeekEvent()
        val ready = coordinator.acceptReady(needsFrameConfirmation = false)
        assertEquals(spec, ready?.spec)
        assertEquals(reissued, ready?.serial)
    }

    @Test
    fun `a START_FILE token is taken from the load queue head`() {
        val coordinator = MpvLoadCoordinator()

        val first = coordinator.start("root/ep1.mkv")
        assertEquals(first.token, coordinator.pendingLoadToken())

        assertEquals(first, coordinator.onFileLoaded("root/ep1.mkv"))
        assertEquals(null, coordinator.pendingLoadToken())

        val second = coordinator.start("root/ep2.mkv")
        assertEquals(second.token, coordinator.pendingLoadToken())
    }

    @Test
    fun `the old END_FILE is consumed before the next START_FILE token`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.onFileLoaded("root/ep1.mkv")
        val second = coordinator.start("root/ep2.mkv")

        // 固定事件顺序：旧文件的 END_FILE 先到并被吞掉，下一个 START_FILE 才归属新加载。
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile().end)
        assertEquals(second.token, coordinator.pendingLoadToken())
    }

    @Test
    fun `a stopped queue exposes no START_FILE token`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.stop()

        // stop 清空在途加载后，迟到的 START_FILE 不会拿到旧令牌。
        assertEquals(null, coordinator.pendingLoadToken())

        val next = coordinator.start("root/ep2.mkv")
        assertEquals(next.token, coordinator.pendingLoadToken())
    }

    @Test
    fun `discarding a dead generation lets the restore load own the next FILE_LOADED`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep2.mkv")
        coordinator.discardInFlight()

        val restored = coordinator.start("root/ep1.mkv")
        // 死代际迟到的 END_FILE 先到：被丢弃，不伤恢复加载；结束计数已收尾。
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile().end)
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

        assertEquals(MpvFileEvents.End.Ended, coordinator.onEndFile().end)
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
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile().end)
        assertEquals(next, coordinator.onFileLoaded("root/ep2.mkv"))
        assertEquals(next, coordinator.acceptReady(needsFrameConfirmation = false)?.spec)
    }

    @Test
    fun `a retargeted load is accepted before the superseded target ends`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.onFileLoaded("root/ep1.mkv")
        coordinator.start("root/ep2.mkv")
        val finalTarget = coordinator.start("root/ep3.mkv")

        // ep2 被再次切集取代，它的 END_FILE 迟到；ep3 的 FILE_LOADED 不能因此卡死。
        assertEquals(finalTarget, coordinator.onFileLoaded("root/ep3.mkv"))
        assertEquals(finalTarget, coordinator.acceptReady(needsFrameConfirmation = false)?.spec)

        // 被取代目标的 END_FILE 仍按替换吞掉，不影响已加载的最终目标。
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile().end)
        assertEquals(finalTarget.token, coordinator.currentLoadedToken())
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
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile().end)
        assertEquals(replacement, coordinator.onFileLoaded("root/ep1.mkv"))
    }

    @Test
    fun `a late FILE_LOADED for a stopped in-flight same URL load is rejected`() {
        val coordinator = MpvLoadCoordinator()
        coordinator.start("root/ep1.mkv")
        coordinator.stop()
        val replacement = coordinator.start("root/ep1.mkv")

        assertEquals(null, coordinator.onFileLoaded("root/ep1.mkv"))
        assertEquals(MpvFileEvents.End.Replaced, coordinator.onEndFile().end)
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
    fun `mpv error logs are attributed to the load token and keep the root cause`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO, "普通信息")
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "打开文件失败")
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "  ")
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN, "警告不算错误")
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "解码失败")

        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u1", 1)))
        assertEquals(listOf(1 to "cplayer: 打开文件失败 | ffmpeg: 解码失败"), failures)

        // 读取后只清理该令牌：下一次 END_FILE 不再带出同一文本。
        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u1", 1)))
        assertEquals(listOf(1 to "cplayer: 打开文件失败 | ffmpeg: 解码失败", 1 to null), failures)
    }

    @Test
    fun `mpv error logs keep the first error and the latest capacity`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        bridge.logMessage("a", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "1")
        bridge.logMessage("a", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "2")
        bridge.logMessage("a", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "3")
        bridge.logMessage("a", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "4")

        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u1", 1)))

        // 首条根因不能被后续错误挤掉。
        assertEquals(listOf(1 to "a: 1 | a: 3 | a: 4"), failures)
    }

    @Test
    fun `opening errors before START_FILE stay with their own load`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        // 打开阶段失败可能没有 START_FILE：start 之后到达的日志仍归本次加载。
        bridge.onStartLoad(1)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "打开失败")

        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u1", 1)))
        assertEquals(listOf(1 to "cplayer: 打开失败"), failures)
    }

    @Test
    fun `error capacity is per load token`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        listOf("a1", "a2", "a3", "a4").forEach {
            bridge.logMessage("a", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, it)
        }
        bridge.onStartLoad(2)
        listOf("b1", "b2", "b3", "b4").forEach {
            bridge.logMessage("b", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, it)
        }

        // 旧令牌残留不能挤掉本轮的首条根因。
        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u2", 2)))
        assertEquals(listOf(2 to "b: b1 | b: b3 | b: b4"), failures)
    }

    @Test
    fun `a null START_FILE token does not attach logs to the previous load`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "旧文件错误")
        // 队列已被 stop 清空：START_FILE 拿不到令牌，之后的日志不能再挂到旧加载。
        bridge.onStartFile(null)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "停止后日志")

        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u1", 1)))
        assertEquals(listOf(1 to "cplayer: 旧文件错误"), failures)
    }

    @Test
    fun `a late START_FILE for an older load does not move the failure anchor`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(2)
        bridge.onStartFile(2)
        bridge.onStartLoad(3)
        // 旧 START_FILE 迟到：activeToken 可以切回旧令牌，但兜底锚点必须仍是令牌 3。
        bridge.onStartFile(2)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "令牌 3 的打开失败")

        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u3", 3)))
        assertEquals(listOf(3 to "cplayer: 令牌 3 的打开失败"), failures)
    }

    @Test
    fun `a replaced END_FILE drops old unload logs mixed into the new load`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "旧文件错误")

        bridge.onStartLoad(2)
        // start 后、START_FILE 前到达的旧文件卸载日志会按新令牌入桶。
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "旧文件卸载错误")
        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Replaced, LoadSpec("u1", 1)))

        // 新加载没有 START_FILE 就失败：不能把旧卸载日志当成本轮错误文本。
        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u2", 2)))
        assertEquals(listOf(2 to null), failures)
    }

    @Test
    fun `a late log from the previous load does not enter the next load's error text`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        // 新加载已下发但本轮 START_FILE 还没到：排队日志暂时挂在本轮，随后由 START_FILE 清掉。
        bridge.onStartLoad(2)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "上一轮迟到错误")
        bridge.onStartFile(2)
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "本轮错误")

        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u2", 2)))
        assertEquals(listOf(2 to "ffmpeg: 本轮错误"), failures)
    }

    @Test
    fun `a replaced END_FILE keeps the current load's error text`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "旧文件错误")
        bridge.onStartLoad(2)
        bridge.onStartFile(2)
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "本轮错误")

        // 旧目标被替换的结束只清理旧令牌，不能清掉本轮文本。
        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Replaced, LoadSpec("u1", 1)))
        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u2", 2)))
        assertEquals(listOf(2 to "ffmpeg: 本轮错误"), failures)
    }

    @Test
    fun `START_FILE only clears entries of the same token`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "旧文件错误")
        bridge.onStartLoad(2)
        bridge.onStartFile(2)
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "本轮旧条目")
        bridge.onStartFile(2)
        bridge.logMessage("ffmpeg", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "本轮错误")

        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Replaced, LoadSpec("u1", 1)))
        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, LoadSpec("u2", 2)))
        assertEquals(listOf(2 to "ffmpeg: 本轮错误"), failures)
    }

    @Test
    fun `an END_FILE without its load is not reported as a failure`() {
        val failures = mutableListOf<Pair<Int, String?>>()
        val bridge = MpvErrorLogBridge { token, text -> failures += token to text }

        bridge.onStartLoad(1)
        bridge.onStartFile(1)
        bridge.logMessage("cplayer", MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR, "无法归属的错误")
        bridge.onEndFile(LoadEnd(MpvFileEvents.End.Failed, spec = null))

        assertTrue(failures.isEmpty())
    }
}
