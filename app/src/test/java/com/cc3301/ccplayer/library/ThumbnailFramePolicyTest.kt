package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailFramePolicyTest {

    @Test
    fun `本地视频按比例抽三帧并含三分之一兜底`() {
        val positions = framePositions(100_000, LOCAL_FRAME_COUNT)

        assertEquals(3, positions.size)
        assertEquals(10_000_000L, positions[0].micros)
        assertEquals(33_333_333L, positions[1].micros)
        assertEquals(66_666_666L, positions[2].micros)
        assertFalse(positions[0].fallback)
        assertTrue(positions[1].fallback)
        assertFalse(positions[2].fallback)
    }

    @Test
    fun `网络视频最多抽两帧且从三分之一处开始`() {
        val positions = framePositions(90_000, NETWORK_FRAME_COUNT)

        assertEquals(2, positions.size)
        assertEquals(30_000_000L, positions[0].micros)
        assertEquals(60_000_000L, positions[1].micros)
        assertTrue(positions[0].fallback)
    }

    @Test
    fun `时长未知时取多个固定候选且最后一个兜底`() {
        val local = framePositions(0, LOCAL_FRAME_COUNT)

        assertEquals(listOf(1_000_000L, 3_000_000L, 5_000_000L), local.map { it.micros })
        assertEquals(listOf(false, false, true), local.map { it.fallback })

        val network = framePositions(0, NETWORK_FRAME_COUNT)
        assertEquals(listOf(1_000_000L, 3_000_000L), network.map { it.micros })
        assertTrue(network.last().fallback)
    }

    @Test
    fun `帧数为零时不返回位置`() {
        assertTrue(framePositions(100_000, 0).isEmpty())
        assertTrue(framePositions(0, 0).isEmpty())
    }

    @Test
    fun `所有候选解码失败时没有可用帧`() {
        val decoded = decodedFrames<Int>(framePositions(100_000, LOCAL_FRAME_COUNT)) { null }

        assertTrue(decoded.isEmpty())
        assertNull(chooseFrame(decoded) { false })
    }

    @Test
    fun `纯黑与亮度差很小的帧判为纯色`() {
        assertTrue(isSolidLuminance(intArrayOf(0, 0, 0, 0)))
        assertTrue(isSolidLuminance(intArrayOf(120, 128, 124, 126)))
    }

    @Test
    fun `有明显明暗变化的帧不是纯色`() {
        assertFalse(isSolidLuminance(intArrayOf(0, 40, 80, 120)))
        assertFalse(isSolidLuminance(intArrayOf(8, 60)))
    }

    @Test
    fun `优先选第一帧非纯色`() {
        val frames = listOf(
            FramePosition(1, fallback = false) to "黑帧",
            FramePosition(2, fallback = true) to "正常帧",
            FramePosition(3, fallback = false) to "另一帧",
        )

        assertEquals("正常帧", chooseFrame(frames) { it == "黑帧" })
    }

    @Test
    fun `全部不合格时退回三分之一处`() {
        val frames = listOf(
            FramePosition(1, fallback = false) to "片头黑帧",
            FramePosition(2, fallback = true) to "三分之一黑帧",
            FramePosition(3, fallback = false) to "片尾黑帧",
        )

        assertEquals("三分之一黑帧", chooseFrame(frames) { true })
    }

    @Test
    fun `没有可用帧时返回空`() {
        assertNull(chooseFrame(emptyList<Pair<FramePosition, String>>()) { true })
    }

    @Test
    fun `抽帧输出宽度钳在 320 到 512`() {
        assertEquals(512, targetFrameWidth(1920))
        assertEquals(512, targetFrameWidth(513))
        assertEquals(400, targetFrameWidth(400))
        assertEquals(320, targetFrameWidth(300))
        assertEquals(320, targetFrameWidth(100))
        assertEquals(320, targetFrameWidth(1920, targetWidth = 320))
        assertEquals(320, effectiveTargetWidth(100))
        assertEquals(512, effectiveTargetWidth(1_000))
    }
}
