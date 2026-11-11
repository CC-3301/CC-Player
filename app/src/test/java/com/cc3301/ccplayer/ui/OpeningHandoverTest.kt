package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.testVideo
import com.cc3301.ccplayer.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 打开交接：入口阶段、兜底计时与待起播请求的编排。 */
class OpeningHandoverTest {

    private var nowMillis = 0L
    private var controlAccepts = true
    private val starts = mutableListOf<Started>()

    private val current = testVideo("第1集.mkv", "content://root/第1集.mkv")
    private val playlist = listOf(current)
    private val directoryEntries = listOf(current)

    private val handover = OpeningHandover(
        startPlayback = { files, media, generation, entries ->
            starts += Started(files, media, generation, entries)
            controlAccepts
        },
        nowMillis = { nowMillis },
    )

    private data class Started(
        val playlist: List<MediaFile>,
        val current: MediaFile,
        val generation: Int,
        val directoryEntries: List<MediaFile>,
    )

    /** 视频打开走到已接手：准备中 -> 等待接手 -> 已接手。 */
    private fun acceptedEntry(): Int {
        val generation = handover.begin(preparing = true)
        handover.prepared(generation, playlist, current, directoryEntries)
        assertEquals(HandoverAttempt.Accepted, handover.attemptHandover(canStart = true))
        return generation
    }

    private fun preparedEntry(): Int {
        val generation = handover.begin(preparing = true)
        handover.prepared(generation, playlist, current, directoryEntries)
        return generation
    }

    // ---- 入口阶段 ----

    @Test
    fun `视频打开先进入准备中`() {
        handover.begin(preparing = true)

        assertEquals(EntryPhase.Preparing, handover.phase)
        assertTrue(handover.preparingUi)
        assertTrue(handover.initialLoading(PlaybackState.Idle, switching = false))
    }

    @Test
    fun `音频打开同样从准备中开始但不显示准备期界面`() {
        val generation = handover.begin(preparing = false)

        assertEquals(EntryPhase.Preparing, handover.phase)
        assertFalse(handover.preparingUi)
        assertTrue(handover.isCurrent(generation))
    }

    @Test
    fun `准备完成后进入等待接手`() {
        val generation = handover.begin(preparing = true)

        assertTrue(handover.prepared(generation, playlist, current, directoryEntries))

        assertEquals(EntryPhase.AwaitingHandover, handover.phase)
        assertTrue(handover.preparingUi)
        assertTrue(handover.isAwaitingHandover)
    }

    @Test
    fun `接手后恢复界面`() {
        acceptedEntry()

        assertEquals(EntryPhase.Accepted, handover.phase)
        assertFalse(handover.preparingUi)
        assertTrue(handover.initialLoading(PlaybackState.Loading, switching = false))
    }

    @Test
    fun `接手后首次就绪结束入口阶段`() {
        acceptedEntry()

        handover.onPlaybackUpdate(PlaybackState.Playing, switching = false)

        assertNull(handover.phase)
        assertFalse(handover.initialLoading(PlaybackState.Playing, switching = false))
    }

    @Test
    fun `接手后转入切集结束入口阶段`() {
        acceptedEntry()

        handover.onPlaybackUpdate(PlaybackState.Loading, switching = true)

        assertNull(handover.phase)
    }

    @Test
    fun `接手的打开在已结束时报终态出口`() {
        acceptedEntry()

        handover.onPlaybackUpdate(PlaybackState.Ended, switching = false)

        assertNull(handover.phase)
    }

    @Test
    fun `接手的打开在空闲时报终态出口`() {
        acceptedEntry()

        handover.onPlaybackUpdate(PlaybackState.Idle, switching = false)

        assertNull(handover.phase)
    }

    @Test
    fun `准备期不受上一会话的就绪上报影响`() {
        handover.begin(preparing = true)

        handover.onPlaybackUpdate(PlaybackState.Playing, switching = false)

        assertEquals(EntryPhase.Preparing, handover.phase)
        assertTrue(handover.preparingUi)
    }

    @Test
    fun `等待接手不受上一会话的就绪上报影响`() {
        preparedEntry()

        handover.onPlaybackUpdate(PlaybackState.Paused, switching = false)

        assertEquals(EntryPhase.AwaitingHandover, handover.phase)
    }

    @Test
    fun `旧代际的准备完成被丢弃`() {
        val first = handover.begin(preparing = true)
        val second = handover.begin(preparing = true)

        assertFalse(handover.prepared(first, playlist, current, directoryEntries))
        assertEquals(EntryPhase.Preparing, handover.phase)
        assertTrue(handover.prepared(second, playlist, current, directoryEntries))
    }

    @Test
    fun `旧代际的准备失败被丢弃`() {
        val first = handover.begin(preparing = true)
        handover.begin(preparing = true)

        assertFalse(handover.cancel(first))
        assertEquals(EntryPhase.Preparing, handover.phase)
    }

    @Test
    fun `失败后立即作废代际`() {
        val generation = handover.begin(preparing = false)

        assertTrue(handover.cancel(generation))
        assertNull(handover.phase)
        assertFalse(handover.isCurrent(generation))
        assertFalse(handover.cancel(generation))
    }

    @Test
    fun `取消打开后阶段与代际都清空`() {
        val generation = handover.begin(preparing = true)
        handover.onLeftPlayerPage()

        assertNull(handover.phase)
        assertFalse(handover.preparingUi)
        assertFalse(handover.isCurrent(generation))
        assertFalse(handover.prepared(generation, playlist, current, directoryEntries))
        assertFalse(handover.cancel(generation))
    }

    @Test
    fun `准备期的加载失败不算首次加载失败`() {
        val generation = handover.begin(preparing = true)

        assertFalse(handover.consumeInitialFailure(generation, firstLoad = true))
        assertEquals(EntryPhase.Preparing, handover.phase)
    }

    @Test
    fun `接手前旧代际的首次加载失败被丢弃`() {
        val generation = acceptedEntry()
        val stale = generation - 1

        assertFalse(handover.consumeInitialFailure(stale, firstLoad = true))
        assertEquals(EntryPhase.Accepted, handover.phase)
    }

    @Test
    fun `接手的首次加载失败只消费一次`() {
        val generation = acceptedEntry()

        assertTrue(handover.consumeInitialFailure(generation, firstLoad = true))
        assertNull(handover.phase)
        assertFalse(handover.consumeInitialFailure(generation, firstLoad = true))
    }

    @Test
    fun `首次就绪后的后续失败不关播放页`() {
        val generation = acceptedEntry()
        handover.onPlaybackUpdate(PlaybackState.Playing, switching = false)

        assertFalse(handover.consumeInitialFailure(generation, firstLoad = true))
        assertNull(handover.phase)
    }

    @Test
    fun `切集后的后续失败不关播放页`() {
        val generation = acceptedEntry()
        handover.onPlaybackUpdate(PlaybackState.Loading, switching = true)

        assertFalse(handover.consumeInitialFailure(generation, firstLoad = true))
    }

    @Test
    fun `非首次加载失败不关播放页`() {
        val generation = acceptedEntry()

        assertFalse(handover.consumeInitialFailure(generation, firstLoad = false))
        assertEquals(EntryPhase.Accepted, handover.phase)
    }

    @Test
    fun `音频首播失败留在播放页`() {
        val generation = handover.begin(preparing = false)
        handover.prepared(generation, playlist, current, directoryEntries)
        handover.attemptHandover(canStart = true)

        assertFalse(handover.consumeInitialFailure(generation, firstLoad = true))
    }

    @Test
    fun `首次加载失败在入口阶段已结束后仍按代际收尾`() {
        val generation = acceptedEntry()
        handover.onPlaybackUpdate(PlaybackState.Ended, switching = false)
        assertNull(handover.phase)

        assertTrue(handover.consumeInitialFailure(generation, firstLoad = true))
        assertNull(handover.phase)
    }

    @Test
    fun `准备期显示缓冲指示`() {
        val generation = handover.begin(preparing = true)
        assertTrue(handover.showsBuffering(controllerBuffering = false))

        handover.prepared(generation, playlist, current, directoryEntries)
        assertTrue(handover.showsBuffering(controllerBuffering = false))
    }

    @Test
    fun `接手后缓冲指示延续到首个就绪`() {
        acceptedEntry()

        assertTrue(handover.showsBuffering(controllerBuffering = false))
        handover.onPlaybackUpdate(PlaybackState.Loading, switching = false)
        assertTrue(handover.showsBuffering(controllerBuffering = false))

        handover.onPlaybackUpdate(PlaybackState.Paused, switching = false)
        assertFalse(handover.showsBuffering(controllerBuffering = false))
        assertTrue(handover.showsBuffering(controllerBuffering = true))
    }

    @Test
    fun `音频进入时不显示入口缓冲`() {
        val generation = handover.begin(preparing = false)
        handover.prepared(generation, playlist, current, directoryEntries)
        handover.attemptHandover(canStart = true)

        assertFalse(handover.showsBuffering(controllerBuffering = false))
        assertTrue(handover.showsBuffering(controllerBuffering = true))
        assertEquals(EntryPhase.Accepted, handover.phase)
    }

    @Test
    fun `新打开分配新的代际`() {
        val first = handover.begin(preparing = true)
        handover.onLeftPlayerPage()
        val second = handover.begin(preparing = true)

        assertTrue(second > first)
        assertTrue(handover.isCurrent(second))
        assertFalse(handover.isCurrent(first))
    }

    // ---- 兜底计时 ----

    @Test
    fun `按实际时间差累计到点`() {
        preparedEntry()

        nowMillis = 1_000
        assertFalse(handover.advance())
        nowMillis = 4_999
        assertFalse(handover.advance())
        nowMillis = 5_000
        assertTrue(handover.advance())
    }

    @Test
    fun `暂停期间不计入等待`() {
        preparedEntry()
        nowMillis = 2_000
        assertFalse(handover.advance())

        handover.onForegroundChanged(false)
        nowMillis = 60_000
        assertFalse(handover.advance())

        handover.onForegroundChanged(true)
        nowMillis = 62_000
        assertFalse(handover.advance())
        nowMillis = 63_000
        assertTrue(handover.advance())
    }

    @Test
    fun `重置后从头计时`() {
        preparedEntry()
        nowMillis = 4_000
        assertFalse(handover.advance())

        preparedEntry()
        nowMillis = 8_000
        assertFalse(handover.advance())
        nowMillis = 9_000
        assertTrue(handover.advance())
    }

    @Test
    fun `兜底到点按失败收尾并回滚`() {
        preparedEntry()

        nowMillis = 5_000
        assertTrue(handover.advance())
        assertNull(handover.phase)
        // 有旧会话且不在片尾：撤回打开交接；没有旧会话：结束播放。
        assertEquals(
            FailedEntryExit.LeavePlayerPage,
            failedEntryExit(PlaybackState.Playing, atEpisodeEnd = false),
        )
        assertEquals(
            FailedEntryExit.StopPlayback,
            failedEntryExit(PlaybackState.Idle, atEpisodeEnd = false),
        )
    }

    @Test
    fun `已接手后不再累计兜底`() {
        acceptedEntry()

        nowMillis = 60_000
        assertFalse(handover.advance())
        assertEquals(EntryPhase.Accepted, handover.phase)
    }

    // ---- 待起播 ----

    @Test
    fun `服务与画面未就绪时不开始播放`() {
        val generation = handover.begin(preparing = true)
        handover.prepared(generation, playlist, current, directoryEntries)

        assertEquals(HandoverAttempt.NotYet, handover.attemptHandover(canStart = false))

        assertTrue(starts.isEmpty())
    }

    @Test
    fun `就绪后把播放列表与代际交给播放控制层且只起播一次`() {
        val generation = preparedEntry()

        assertEquals(HandoverAttempt.Accepted, handover.attemptHandover(canStart = true))
        assertEquals(HandoverAttempt.NotYet, handover.attemptHandover(canStart = true))

        assertEquals(1, starts.size)
        assertEquals(current, starts.single().current)
        assertEquals(playlist, starts.single().playlist)
        assertEquals(directoryEntries, starts.single().directoryEntries)
        assertEquals(listOf(generation), starts.map { it.generation })
    }

    @Test
    fun `从未就绪到就绪只起播一次`() {
        preparedEntry()

        handover.attemptHandover(canStart = false)
        handover.attemptHandover(canStart = true)
        handover.attemptHandover(canStart = true)

        assertEquals(1, starts.size)
    }

    @Test
    fun `清空后不再起播`() {
        preparedEntry()
        handover.onLeftPlayerPage()

        assertEquals(HandoverAttempt.NotYet, handover.attemptHandover(canStart = true))

        assertTrue(starts.isEmpty())
    }

    @Test
    fun `控制层不接受时作废这次打开且不重复起播`() {
        controlAccepts = false
        val generation = preparedEntry()

        assertEquals(HandoverAttempt.Rejected, handover.attemptHandover(canStart = true))

        assertNull(handover.phase)
        assertFalse(handover.isCurrent(generation))
        assertEquals(HandoverAttempt.NotYet, handover.attemptHandover(canStart = true))
        assertEquals(1, starts.size)
    }
}
