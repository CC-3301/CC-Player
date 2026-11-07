package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加载代际的状态机：旧代际回调一律丢弃、改目标与重发沿用同一代际、出声归属随代际存在与消失。
 */
class LoadGenerationTest {

    private var nowMillis = 0L

    private fun generation(id: Int = 1): LoadGeneration = LoadGeneration(id) { nowMillis }

    private fun readySnapshot(): HandoverSnapshot = HandoverSnapshot(
        kind = LoadKind.First,
        screenFrame = ScreenFrame.Target,
        originAspectRatio = 16.0 / 9.0,
        holdBlack = false,
        quietBlack = false,
        audioOwner = AudioOwner.Target,
    )

    @Test
    fun `打开交接后旧目标迟到的回调被丢弃`() {
        val generation = generation()
        generation.targetIndex = 0
        generation.targetUrl = "root/a.mkv"
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
    fun `新目标出画面才解除交接静音`() {
        val generation = generation()
        generation.startHandover(readySnapshot())
        generation.retarget(1, "root/b.mkv", 8)

        assertTrue(generation.accepts(8, "root/b.mkv"))
        assertEquals(AudioOwner.Opening, generation.audioOwner)
        assertTrue(mutedByOpening(generation, previewMuted = false))

        assertTrue(generation.releaseAudioOwnership())
        assertEquals(AudioOwner.Target, generation.audioOwner)
        assertFalse(mutedByOpening(generation, previewMuted = false))
    }

    @Test
    fun `重发沿用同一代际并仍能放声`() {
        val generation = generation(id = 3)
        generation.startHandover(readySnapshot())
        generation.retarget(1, "root/b.mkv", 8)

        // 重发只换这次引擎加载的令牌：代际、目标与出声归属都不变。
        generation.beginAttempt(9)

        assertEquals(3, generation.id)
        assertEquals(2, generation.attempts)
        assertEquals("root/b.mkv", generation.targetUrl)
        assertTrue(generation.accepts(9, "root/b.mkv"))
        assertFalse(generation.accepts(8, "root/b.mkv"))
        assertEquals(AudioOwner.Opening, generation.audioOwner)

        // 重发后的加载就绪仍然解除交接静音：令牌换了不影响出声归属。
        assertTrue(generation.releaseAudioOwnership())
        assertFalse(mutedByOpening(generation, previewMuted = false))
    }

    @Test
    fun `改目标沿用同一代际并只更新目标与次数`() {
        val generation = generation(id = 4)
        generation.beginAttempt(1)
        generation.retarget(2, "root/c.mkv", 2)

        assertEquals(4, generation.id)
        assertEquals(2, generation.attempts)
        assertEquals(2, generation.targetIndex)
        assertEquals("root/c.mkv", generation.targetUrl)
        assertFalse(generation.accepts(1, null))
        assertTrue(generation.accepts(2, "root/c.mkv"))
    }

    @Test
    fun `撤销交接恢复交接前的画面与出声`() {
        val generation = generation()
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
        generation.startHandover(readySnapshot())
        generation.clearHandover()

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
        generation.targetUrl = "root/a.mkv"

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
