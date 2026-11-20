package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加载代际的状态机：每种加载路径走自己的迁移、旧代际回调一律丢弃、改目标与重发沿用同一代际、
 * 出声归属与屏上画面随代际存在与消失。
 */
class LoadGenerationTest {

    private var nowMillis = 0L

    private fun generation(): LoadGeneration = LoadGeneration { nowMillis }

    private fun readySnapshot(): HandoverSnapshot = HandoverSnapshot(
        kind = LoadKind.First,
        screenFrame = ScreenFrame.Target,
        originAspectRatio = 16.0 / 9.0,
        holdBlack = false,
        quietBlack = false,
        audioOwner = AudioOwner.Target,
    )

    @Test
    fun `首次打开从黑屏开始且目标可以出声`() {
        val generation = generation()

        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")

        assertEquals(LoadKind.First, generation.kind)
        assertEquals(LoadPhase.Initial, generation.phase)
        assertEquals(0, generation.targetIndex)
        assertEquals("root/a.mkv", generation.targetUrl)
        assertEquals(AudioOwner.Target, generation.audioOwner)
        assertEquals(ScreenFrame.None, generation.screenFrame)
        assertNull(generation.originAspectRatio)
        assertFalse(generation.holdBlack)
        assertFalse(generation.quietBlack)
    }

    @Test
    fun `打开交接后旧目标迟到的回调被丢弃`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.beginAttempt(7)
        assertTrue(generation.accepts(7, "root/a.mkv"))

        generation.startHandover(readySnapshot())

        // 交接一开始旧令牌就作废：旧目标的完成、出帧与失败都不再属于当前代际。
        assertFalse(generation.accepts(7, "root/a.mkv"))
        assertTrue(generation.isHandoverPending)
        assertEquals(AudioOwner.Opening, generation.audioOwner)
        assertEquals(ScreenFrame.None, generation.screenFrame)
        assertNull(generation.originAspectRatio)
        assertTrue(generation.quietBlack)
    }

    @Test
    fun `打开新目标保持静默黑屏直到就绪`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.beginAttempt(7)
        generation.startHandover(readySnapshot())

        generation.beginOpen(targetIndex = 1, targetUrl = "root/b.mkv")

        assertEquals(LoadKind.Open, generation.kind)
        assertEquals(LoadPhase.Initial, generation.phase)
        assertEquals(1, generation.targetIndex)
        assertEquals("root/b.mkv", generation.targetUrl)
        assertNull(generation.handover)
        assertEquals(AudioOwner.Opening, generation.audioOwner)
        assertTrue(generation.quietBlack)
        assertEquals(ScreenFrame.None, generation.screenFrame)
    }

    @Test
    fun `新目标出画面才解除交接静音`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.startHandover(readySnapshot())
        generation.beginOpen(targetIndex = 1, targetUrl = "root/b.mkv")
        generation.beginAttempt(8)

        assertTrue(generation.accepts(8, "root/b.mkv"))
        assertEquals(AudioOwner.Opening, generation.audioOwner)
        assertTrue(mutedByOpening(generation, previewMuted = false))

        assertTrue(generation.releaseAudioOwnership())
        assertEquals(AudioOwner.Target, generation.audioOwner)
        assertFalse(mutedByOpening(generation, previewMuted = false))
    }

    @Test
    fun `重发沿用同一代际并仍能放声`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.startHandover(readySnapshot())
        generation.beginOpen(targetIndex = 1, targetUrl = "root/b.mkv")
        generation.beginAttempt(8)

        // 重发只换这次引擎加载的令牌：代际、目标与出声归属都不变。
        generation.beginAttempt(9)

        assertEquals("root/b.mkv", generation.targetUrl)
        assertTrue(generation.accepts(9, "root/b.mkv"))
        assertFalse(generation.accepts(8, "root/b.mkv"))
        assertEquals(AudioOwner.Opening, generation.audioOwner)

        // 重发后的加载就绪仍然解除交接静音：令牌换了不影响出声归属。
        assertTrue(generation.releaseAudioOwnership())
        assertFalse(mutedByOpening(generation, previewMuted = false))
    }

    @Test
    fun `一次新尝试清掉上一轮的加载与就绪证据`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.beginAttempt(1)
        generation.noteMediaLoaded(hasVideo = false)
        generation.noteResumeSeek(requiredSeekSerial = 3)
        generation.deferReady(true)
        assertTrue(generation.mediaLoaded)
        assertFalse(generation.hasVideo)
        assertTrue(generation.seekPendingForReady)
        assertEquals(3, generation.requiredSeekSerial)

        generation.beginAttempt(2)

        assertFalse(generation.mediaLoaded)
        assertTrue(generation.hasVideo)
        assertFalse(generation.seekPendingForReady)
        assertNull(generation.deferredReadyNeedsFrame)
        assertEquals(2, generation.loadToken)
    }

    @Test
    fun `改目标沿用同一代际，旧令牌作废`() {
        val generation = generation()
        generation.beginSwitch(
            targetIndex = 1,
            targetUrl = "root/b.mkv",
            inheritOrigin = false,
            originAspectRatio = null,
            quietBlack = false,
        )
        generation.beginAttempt(1)

        generation.retarget(targetIndex = 2, targetUrl = "root/c.mkv")

        assertEquals(2, generation.targetIndex)
        assertEquals("root/c.mkv", generation.targetUrl)
        assertEquals(LoadPhase.Switching, generation.phase)
        generation.beginAttempt(2)
        assertFalse(generation.accepts(1, null))
        assertTrue(generation.accepts(2, "root/c.mkv"))
    }

    @Test
    fun `切集沿用原片画面与比例，出声由引擎决定`() {
        val generation = generation()

        generation.beginSwitch(
            targetIndex = 1,
            targetUrl = "root/b.mkv",
            inheritOrigin = true,
            originAspectRatio = 16.0 / 9.0,
            quietBlack = false,
        )

        assertEquals(LoadKind.Switch, generation.kind)
        assertEquals(LoadPhase.Switching, generation.phase)
        assertEquals(ScreenFrame.Origin, generation.screenFrame)
        assertEquals(16.0 / 9.0, generation.originAspectRatio!!, 0.0001)
        assertEquals(AudioOwner.None, generation.audioOwner)
        assertFalse(generation.holdBlack)
        assertFalse(generation.quietBlack)
    }

    @Test
    fun `后台切集不沿用画面并静默黑屏`() {
        val generation = generation()

        generation.beginSwitch(
            targetIndex = 1,
            targetUrl = "root/b.mkv",
            inheritOrigin = false,
            originAspectRatio = null,
            quietBlack = true,
        )

        assertEquals(ScreenFrame.None, generation.screenFrame)
        assertNull(generation.originAspectRatio)
        assertTrue(generation.quietBlack)
    }

    @Test
    fun `恢复原片沿用屏上画面与黑底`() {
        val generation = generation()

        generation.beginRestore(
            targetIndex = 0,
            targetUrl = "root/a.mkv",
            screenFrame = ScreenFrame.Origin,
            originAspectRatio = 4.0 / 3.0,
            holdBlack = true,
        )

        assertEquals(LoadKind.Restore, generation.kind)
        assertEquals(LoadPhase.Restoring, generation.phase)
        assertEquals(0, generation.targetIndex)
        assertEquals("root/a.mkv", generation.targetUrl)
        assertEquals(ScreenFrame.Origin, generation.screenFrame)
        assertEquals(4.0 / 3.0, generation.originAspectRatio!!, 0.0001)
        assertTrue(generation.holdBlack)
        assertEquals(AudioOwner.None, generation.audioOwner)
        assertFalse(generation.quietBlack)
    }

    @Test
    fun `媒体加载完成记下目标有没有视频轨`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.beginAttempt(1)

        generation.noteMediaLoaded(hasVideo = false)

        assertTrue(generation.mediaLoaded)
        assertFalse(generation.hasVideo)
    }

    @Test
    fun `目标就绪结束加载并清掉就绪证据`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.beginAttempt(1)
        generation.noteMediaLoaded(hasVideo = true)
        generation.noteResumeSeek(requiredSeekSerial = 2)
        generation.deferReady(true)

        generation.markReady()

        assertEquals(LoadPhase.None, generation.phase)
        assertNull(generation.handover)
        assertFalse(generation.seekPendingForReady)
        assertNull(generation.deferredReadyNeedsFrame)
        assertTrue(generation.mediaLoaded)
    }

    @Test
    fun `续播 seek 之后的首帧才解除就绪等待`() {
        val generation = generation()

        generation.noteResumeSeek(requiredSeekSerial = 3)

        assertTrue(generation.seekPendingForReady)
        assertEquals(3, generation.requiredSeekSerial)

        generation.settleResumeSeek()

        assertFalse(generation.seekPendingForReady)
        assertEquals(3, generation.requiredSeekSerial)
    }

    @Test
    fun `延后的首帧信号只能取一次`() {
        val generation = generation()
        generation.deferReady(true)

        assertEquals(true, generation.takeDeferredReady())
        assertNull(generation.takeDeferredReady())
    }

    @Test
    fun `开始新一轮加载清掉上一轮的画面与就绪证据`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.beginAttempt(1)
        generation.noteMediaLoaded(hasVideo = true)
        generation.showTargetFrame()
        generation.noteResumeSeek(requiredSeekSerial = 2)
        generation.noteOutputLive()

        generation.resetReadyForLoad(resumeLookupDone = false)

        assertEquals(ScreenFrame.None, generation.screenFrame)
        assertFalse(generation.mediaLoaded)
        assertFalse(generation.seekPendingForReady)
        assertEquals(0, generation.requiredSeekSerial)
        assertFalse(generation.outputLive)
        assertFalse(generation.resumeLookupDone)
        assertTrue(generation.hasVideo)
    }

    @Test
    fun `新帧上屏才把画面交给本代际`() {
        val generation = generation()
        generation.beginSwitch(
            targetIndex = 1,
            targetUrl = "root/b.mkv",
            inheritOrigin = true,
            originAspectRatio = 16.0 / 9.0,
            quietBlack = true,
        )

        generation.showTargetFrame()

        assertEquals(ScreenFrame.Target, generation.screenFrame)
        assertFalse(generation.quietBlack)
    }

    @Test
    fun `撤帧、挂黑底与静默黑屏都由代际记`() {
        val generation = generation()
        generation.beginSwitch(
            targetIndex = 1,
            targetUrl = "root/b.mkv",
            inheritOrigin = true,
            originAspectRatio = 16.0 / 9.0,
            quietBlack = false,
        )

        // 撤帧不改变这次黑屏是否显示提示。
        generation.dropFrame()
        assertEquals(ScreenFrame.None, generation.screenFrame)
        assertNull(generation.originAspectRatio)
        assertFalse(generation.quietBlack)

        generation.dropFrameQuietly()
        assertEquals(ScreenFrame.None, generation.screenFrame)
        assertTrue(generation.quietBlack)

        generation.setQuietBlack(false)
        assertFalse(generation.quietBlack)

        // 接管信号到了却等不到帧：撤帧并按黑底等原片重新出帧。
        generation.showTargetFrame()
        generation.abandonFrame()
        assertEquals(ScreenFrame.None, generation.screenFrame)
        assertTrue(generation.holdBlack)

        // 用户操作解除片尾黑底。
        generation.setHoldBlack(false)
        assertFalse(generation.holdBlack)
    }

    @Test
    fun `接管视频输出由代际记下`() {
        val generation = generation()
        generation.beginSwitch(
            targetIndex = 1,
            targetUrl = "root/b.mkv",
            inheritOrigin = false,
            originAspectRatio = null,
            quietBlack = true,
        )

        generation.noteOutputLive()

        assertTrue(generation.outputLive)
    }

    @Test
    fun `撤销交接恢复交接前的画面与出声`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.beginAttempt(7)
        val snapshot = readySnapshot()

        generation.startHandover(snapshot)
        generation.restoreHandover(snapshot)

        assertEquals(LoadKind.First, generation.kind)
        assertEquals(LoadPhase.None, generation.phase)
        assertEquals(ScreenFrame.Target, generation.screenFrame)
        assertEquals(16.0 / 9.0, generation.originAspectRatio!!, 0.0001)
        assertEquals(AudioOwner.Target, generation.audioOwner)
        assertFalse(generation.quietBlack)
        assertNull(generation.handover)
    }

    @Test
    fun `快照随新目标开始加载失效`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")
        generation.startHandover(readySnapshot())

        generation.beginOpen(targetIndex = 1, targetUrl = "root/b.mkv")

        assertNull(generation.handover)
    }

    @Test
    fun `预览静音与交接静音各管一份`() {
        assertFalse(mutedByOpening(null, previewMuted = false))
        assertTrue(mutedByOpening(null, previewMuted = true))

        val generation = generation()
        generation.startHandover(null)
        assertTrue(mutedByOpening(generation, previewMuted = false))
        assertTrue(mutedByOpening(generation, previewMuted = true))

        generation.releaseAudioOwnership()
        assertFalse(mutedByOpening(generation, previewMuted = false))
        assertTrue(mutedByOpening(generation, previewMuted = true))
    }

    @Test
    fun `代际作废后出声归属随之消失`() {
        val current: LoadGeneration = generation()
        current.startHandover(null)
        assertTrue(mutedByOpening(current, previewMuted = false))

        val discarded: LoadGeneration? = null
        assertFalse(mutedByOpening(discarded, previewMuted = false))
    }

    @Test
    fun `没有在途加载时任何回调都不属于本代际`() {
        val generation = generation()
        generation.beginFirst(targetIndex = 0, targetUrl = "root/a.mkv")

        assertFalse(generation.accepts(NO_LOAD_TOKEN, "root/a.mkv"))
        generation.beginAttempt(5)
        assertFalse(generation.accepts(5, "root/other.mkv"))
        assertTrue(generation.accepts(5, null))
    }

    @Test
    fun `计时按代际自己的累计运行时长起算`() {
        nowMillis = 100
        val generation = generation()
        generation.elapsed.start()

        nowMillis = 350

        assertEquals(250, generation.elapsed.elapsedMillis())
    }
}
