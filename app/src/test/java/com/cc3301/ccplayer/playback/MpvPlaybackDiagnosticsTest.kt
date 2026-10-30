package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.MemoryDiagnosticsSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpvPlaybackDiagnosticsTest {

    @Test
    fun `音频快照覆盖 seek 排查所需的全部字段`() {
        val line = AudioDebugSnapshot(
            paused = false,
            audioOutput = "audiotrack",
            audioPtsSeconds = 30.5,
            timePosSeconds = 31.25,
            eofReached = false,
            audioDeviceConfig = "auto",
            currentAudioOutput = "audiotrack",
            volume = 100.0,
            muted = false,
            audioTrack = "1",
        ).debugText()

        assertEquals(
            "pause=no ao=audiotrack audio-pts=30.500 time-pos=31.250 eof-reached=no " +
                "配置设备=auto 当前ao=audiotrack volume=100.000 muted=no aid=1",
            line,
        )
    }

    @Test
    fun `音量为零时明确记录零值而不是省略`() {
        val line = AudioDebugSnapshot(volume = 0.0, muted = true).debugText()

        assertEquals(
            "pause=- ao=- audio-pts=- time-pos=- eof-reached=- 配置设备=- 当前ao=- " +
                "volume=0.000 muted=yes aid=-",
            line,
        )
    }

    @Test
    fun `seek 前后行携带目标序号剩余时长与音频快照`() {
        val snapshot = AudioDebugSnapshot(paused = true, eofReached = true, volume = 0.0)

        val line = seekDebugLine(
            prefix = "seek 前",
            targetMillis = 90_000,
            serial = 4,
            remainingMillis = 3_000,
            snapshot = snapshot,
        )

        assertEquals(
            "seek 前: 目标=90000ms 序号=4 剩余=3000ms 近末尾=yes(≤5s) " +
                "pause=yes ao=- audio-pts=- time-pos=- eof-reached=yes 配置设备=- 当前ao=- " +
                "volume=0.000 muted=- aid=-",
            line,
        )
    }

    @Test
    fun `时长未知时不判断是否落在末尾附近`() {
        assertNull(remainingToEndMillis(targetMillis = 5_000, durationSeconds = Double.NaN))
        assertNull(remainingToEndMillis(targetMillis = 5_000, durationSeconds = 0.0))

        val line = seekDebugLine(
            prefix = "seek 后(SEEK)",
            targetMillis = 5_000,
            serial = 1,
            remainingMillis = null,
            snapshot = AudioDebugSnapshot(),
        )

        assertEquals(
            "seek 后(SEEK): 目标=5000ms 序号=1 剩余=- 近末尾=- " +
                "pause=- ao=- audio-pts=- time-pos=- eof-reached=- 配置设备=- 当前ao=- " +
                "volume=- muted=- aid=-",
            line,
        )
    }

    @Test
    fun `剩余时长按毫秒计算且不会为负`() {
        assertEquals(60_000L, remainingToEndMillis(targetMillis = 30_000, durationSeconds = 90.0))
        assertEquals(0L, remainingToEndMillis(targetMillis = 95_000, durationSeconds = 90.0))
        assertEquals("no(>5s)", nearEndFlag(5_001L))
        assertEquals("yes(≤5s)", nearEndFlag(5_000L))
    }

    @Test
    fun `淡入行记录实际下发的起点与起始增益`() {
        val line = fadeDebugLine(
            startMillis = 30_000,
            durationMillis = 500,
            issued = true,
            snapshot = AudioDebugSnapshot(volume = 0.0, muted = true),
        )

        assertEquals(
            "淡入: 下发=yes 起始位置=30000ms afade st=30.000s 起始增益=0.000 时长=500ms " +
                "当前音量=0.000 静音=yes",
            line,
        )
    }

    @Test
    fun `淡入未下发时记录否`() {
        val line = fadeDebugLine(
            startMillis = 0,
            durationMillis = 500,
            issued = false,
            snapshot = AudioDebugSnapshot(),
        )

        assertTrue(line.startsWith("淡入: 下发=no "))
    }

    @Test
    fun `seek 前后与重播补记写入日志出口`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = enabledDiagnostics(sink)
        val seekDiagnostics = newSeekDiagnostics(diagnostics)

        seekDiagnostics.register(targetMillis = 30_000, serial = 7)
        seekDiagnostics.logIssued(targetMillis = 30_000, serial = 7)
        seekDiagnostics.onSeekEvent()
        seekDiagnostics.onPlaybackRestart()

        assertEquals(3, sink.lines.size)
        assertTrue(
            sink.lines[0].startsWith(
                "seek 前: 目标=30000ms 序号=7 剩余=90000ms 近末尾=no(>5s) pause=no",
            ),
        )
        assertTrue(
            sink.lines[1].startsWith(
                "seek 后(SEEK): 目标=30000ms 序号=7 剩余=90000ms 近末尾=no(>5s) 排队数=0",
            ),
        )
        assertTrue(
            sink.lines[2].startsWith(
                "seek 后(PLAYBACK_RESTART): 目标=30000ms 序号=7 剩余=90000ms",
            ),
        )
    }

    @Test
    fun `SEEK 事件按 FIFO 消费队首并记录剩余排队数`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = enabledDiagnostics(sink)
        val seekDiagnostics = newSeekDiagnostics(diagnostics)

        listOf(10_000L to 1, 60_000L to 2).forEach { (target, serial) ->
            seekDiagnostics.register(targetMillis = target, serial = serial)
            seekDiagnostics.logIssued(targetMillis = target, serial = serial)
        }

        seekDiagnostics.onSeekEvent()
        val first = sink.lines.last()
        assertTrue(first.startsWith("seek 后(SEEK): 目标=10000ms 序号=1"))
        assertTrue(first.contains("排队数=1"))

        seekDiagnostics.onSeekEvent()
        val second = sink.lines.last()
        assertTrue(second.startsWith("seek 后(SEEK): 目标=60000ms 序号=2"))
        assertTrue(second.contains("排队数=0"))

        // 队列已空，再来一次 SEEK 事件不得重复记旧目标。
        seekDiagnostics.onSeekEvent()
        assertTrue(sink.lines.last().startsWith("seek 后(SEEK): 无对应目标"))

        seekDiagnostics.onPlaybackRestart()
        assertTrue(
            sink.lines.last().startsWith("seek 后(PLAYBACK_RESTART): 目标=60000ms 序号=2"),
        )
    }

    @Test
    fun `SEEK 事件可以在命令执行期间消费已登记的目标`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = enabledDiagnostics(sink)
        val seekDiagnostics = newSeekDiagnostics(diagnostics)
        var observed: String? = null

        seekDiagnostics.register(targetMillis = 10_000, serial = 1)
        // 模拟事件线程在命令返回前到达。
        seekDiagnostics.onSeekEvent()
        observed = sink.lines.last()

        assertTrue(observed!!.startsWith("seek 后(SEEK): 目标=10000ms 序号=1"))
        assertTrue(observed!!.contains("排队数=0"))
    }

    @Test
    fun `关闭期间连续 seek 后重新开启仍按 FIFO 归属`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher())
        val seekDiagnostics = newSeekDiagnostics(diagnostics)

        seekDiagnostics.register(targetMillis = 10_000, serial = 1)
        seekDiagnostics.register(targetMillis = 20_000, serial = 2)
        diagnostics.enabled = true

        seekDiagnostics.onSeekEvent()
        assertTrue(sink.lines.last().startsWith("seek 后(SEEK): 目标=10000ms 序号=1"))
        assertTrue(sink.lines.last().contains("排队数=1"))

        seekDiagnostics.onSeekEvent()
        assertTrue(sink.lines.last().startsWith("seek 后(SEEK): 目标=20000ms 序号=2"))
        assertTrue(sink.lines.last().contains("排队数=0"))
    }

    @Test
    fun `命令失败时回滚登记且下一事件写无对应目标`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = enabledDiagnostics(sink)
        val seekDiagnostics = newSeekDiagnostics(diagnostics)
        val original = IllegalStateException("seek 命令失败")

        val registered = seekDiagnostics.register(targetMillis = 10_000, serial = 1)
        seekDiagnostics.discard(registered, original)

        assertTrue(sink.lines.any { it.startsWith("seek 下发失败: 目标=10000ms 序号=1") })

        seekDiagnostics.onSeekEvent()
        assertTrue(sink.lines.last().startsWith("seek 后(SEEK): 无对应目标"))
        assertFalse(sink.lines.any { it.startsWith("seek 后(SEEK): 目标=") })

        // lastIssued 已回滚，失败的 seek 不应产生 restart 行。
        seekDiagnostics.onPlaybackRestart()
        assertFalse(sink.lines.any { it.startsWith("seek 后(PLAYBACK_RESTART)") })
    }

    @Test
    fun `事件先消费后再回滚不回退 restart 归属`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = enabledDiagnostics(sink)
        val seekDiagnostics = newSeekDiagnostics(diagnostics)
        val original = IllegalStateException("seek 命令失败")

        val registered = seekDiagnostics.register(targetMillis = 10_000, serial = 1)
        // SEEK 事件先消费登记项，命令随后才失败。
        seekDiagnostics.onSeekEvent()
        seekDiagnostics.discard(registered, original)

        assertTrue(sink.lines.any { it.startsWith("seek 后(SEEK): 目标=10000ms 序号=1") })
        assertTrue(sink.lines.any { it.startsWith("seek 下发失败: 目标=10000ms 序号=1") })

        // 该项已被事件消费，lastIssued 不得回滚；restart 仍归属该 seek。
        seekDiagnostics.onPlaybackRestart()
        assertTrue(
            sink.lines.last().startsWith("seek 后(PLAYBACK_RESTART): 目标=10000ms 序号=1"),
        )
    }

    @Test
    fun `淡入整体路径在开启时记录下发成功`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = enabledDiagnostics(sink)
        var commandRan = false

        dispatchFadeIn(
            diagnostics = diagnostics,
            startMillis = 30_000,
            durationMillis = 500,
            command = { commandRan = true },
        ) { AudioDebugSnapshot(volume = 0.0, muted = true) }

        assertTrue(commandRan)
        assertEquals(
            "淡入: 下发=yes 起始位置=30000ms afade st=30.000s 起始增益=0.000 时长=500ms " +
                "当前音量=0.000 静音=yes",
            sink.lines.single(),
        )
    }

    @Test
    fun `淡入命令异常且日志异常时仍抛原异常`() {
        val original = IllegalStateException("淡入命令失败")
        val thrown = runCatching {
            dispatchFadeIn(
                diagnostics = ThrowingDiagnosticsLog(),
                startMillis = 0,
                durationMillis = 500,
                command = { throw original },
            ) { AudioDebugSnapshot() }
        }.exceptionOrNull()

        assertSame(original, thrown)
    }

    @Test
    fun `淡入关闭诊断时不写日志也不求值属性`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher())
        var commandRan = false
        var snapshotCalls = 0

        dispatchFadeIn(
            diagnostics = diagnostics,
            startMillis = 0,
            durationMillis = 500,
            command = { commandRan = true },
        ) {
            snapshotCalls++
            AudioDebugSnapshot()
        }

        assertTrue(commandRan)
        assertTrue(sink.lines.isEmpty())
        assertEquals(0, snapshotCalls)
    }

    @Test
    fun `日志写入抛异常不外抛`() {
        val seekDiagnostics = MpvSeekDiagnostics(
            diagnostics = ThrowingDiagnosticsLog(),
            snapshot = { AudioDebugSnapshot() },
            durationSeconds = { 120.0 },
        )

        seekDiagnostics.register(targetMillis = 10_000, serial = 1)
        seekDiagnostics.logIssued(targetMillis = 10_000, serial = 1)
        seekDiagnostics.discard(
            seekDiagnostics.register(targetMillis = 20_000, serial = 2),
            IllegalStateException("seek 命令失败"),
        )
    }

    @Test
    fun `seek 后使用事件到达时的时长重算剩余`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = enabledDiagnostics(sink)
        var durationSeconds = 0.0
        val seekDiagnostics = MpvSeekDiagnostics(
            diagnostics = diagnostics,
            snapshot = { AudioDebugSnapshot() },
            durationSeconds = { durationSeconds },
        )

        seekDiagnostics.register(targetMillis = 10_000, serial = 1)
        seekDiagnostics.logIssued(targetMillis = 10_000, serial = 1)
        durationSeconds = 120.0
        seekDiagnostics.onSeekEvent()

        assertTrue(sink.lines[0].contains("剩余=- 近末尾=-"))
        assertTrue(
            sink.lines[1].startsWith(
                "seek 后(SEEK): 目标=10000ms 序号=1 剩余=110000ms 近末尾=no(>5s) 排队数=0",
            ),
        )
    }

    @Test
    fun `诊断关闭时不写日志也不求值属性`() {
        val sink = MemoryDiagnosticsSink()
        val diagnostics = Diagnostics(sink, UnconfinedTestDispatcher())
        var snapshotCalls = 0
        var durationCalls = 0
        val seekDiagnostics = MpvSeekDiagnostics(
            diagnostics = diagnostics,
            snapshot = {
                snapshotCalls++
                AudioDebugSnapshot()
            },
            durationSeconds = {
                durationCalls++
                120.0
            },
        )

        seekDiagnostics.register(targetMillis = 10_000, serial = 1)
        seekDiagnostics.logIssued(targetMillis = 10_000, serial = 1)
        seekDiagnostics.onSeekEvent()
        seekDiagnostics.onPlaybackRestart()
        seekDiagnostics.reset()

        assertTrue(sink.lines.isEmpty())
        assertEquals(0, snapshotCalls)
        assertEquals(0, durationCalls)
    }

    private class ThrowingDiagnosticsLog : DiagnosticsLog {
        override val enabled: Boolean = true

        override fun log(message: String): Unit = throw IllegalStateException("日志写入失败")
    }

    private fun enabledDiagnostics(sink: MemoryDiagnosticsSink): Diagnostics =
        Diagnostics(sink, UnconfinedTestDispatcher()).apply { enabled = true }

    private fun newSeekDiagnostics(diagnostics: Diagnostics): MpvSeekDiagnostics =
        MpvSeekDiagnostics(
            diagnostics = diagnostics,
            snapshot = { AudioDebugSnapshot(paused = false, audioPtsSeconds = 30.0) },
            durationSeconds = { 120.0 },
        )
}
