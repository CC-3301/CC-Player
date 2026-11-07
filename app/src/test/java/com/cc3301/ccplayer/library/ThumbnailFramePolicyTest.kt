package com.cc3301.ccplayer.library

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `网络抽帧位置为百分之一时长并封顶 30 秒`() {
        assertEquals(1_000_000L, networkFramePosition(90_000))
        assertEquals(3_000_000L, networkFramePosition(300_000))
        // 1% 超过 30 秒时按 30 秒封顶，几个 GB 的文件不做远距离寻址。
        assertEquals(30_000_000L, networkFramePosition(10_000_000))
    }

    @Test
    fun `网络时长未知时取 1 秒且不取 0 秒`() {
        assertEquals(1_000_000L, networkFramePosition(null))
        assertEquals(1_000_000L, networkFramePosition(0))
        assertEquals(1_000_000L, networkFramePosition(-1))
    }

    @Test
    fun `网络候选为主候选加 60 秒与 90 秒，最多三个`() {
        assertEquals(
            listOf(1_000_000L, 60_000_000L, 90_000_000L),
            networkFrameCandidates(90_000),
        )
        assertEquals(3, networkFrameCandidates(10_000_000).size)
        assertEquals(1_000_000L, networkFrameCandidates(null).first())
    }

    @Test
    fun `网络主候选非纯色时返回该帧且不试退避候选`() = runTest {
        val asked = mutableListOf<Long>()

        val frame = extractNetworkFrame(
            positions = networkFrameCandidates(300_000),
            decode = { micros ->
                asked += micros
                "正常帧"
            },
            isSolid = { false },
        )

        assertEquals("正常帧", frame)
        assertEquals(listOf(3_000_000L), asked)
    }

    @Test
    fun `网络主候选纯色时退到 60 秒与 90 秒候选`() = runTest {
        val asked = mutableListOf<Long>()
        var released = 0

        val frame = extractNetworkFrame(
            positions = networkFrameCandidates(90_000),
            decode = { micros ->
                asked += micros
                if (micros == 90_000_000L) "正常帧" else "纯色帧"
            },
            isSolid = { frame -> frame == "纯色帧" },
            release = { released++ },
        )

        assertEquals("正常帧", frame)
        assertEquals(listOf(1_000_000L, 60_000_000L, 90_000_000L), asked)
        assertEquals(2, released)
    }

    @Test
    fun `网络三个候选都是纯色时保持占位图`() = runTest {
        val asked = mutableListOf<Long>()
        var released = 0

        val frame = extractNetworkFrame(
            positions = networkFrameCandidates(90_000),
            decode = { micros ->
                asked += micros
                "纯色帧"
            },
            isSolid = { true },
            release = { released++ },
        )

        assertNull(frame)
        assertEquals(3, asked.size)
        assertEquals(3, released)
    }

    @Test
    fun `网络主候选解不出时保持占位图且不试退避候选`() = runTest {
        var decodes = 0
        var asked: Long? = null

        val frame = extractNetworkFrame<String>(
            positions = networkFrameCandidates(null),
            decode = { micros ->
                decodes++
                asked = micros
                null
            },
            isSolid = { false },
        )

        assertNull(frame)
        assertEquals(1, decodes)
        assertEquals(1_000_000L, asked)
    }

    @Test
    fun `时长未知时本地从片头开始取固定候选且最后一个兜底`() {
        val local = framePositions(0, LOCAL_FRAME_COUNT)

        assertEquals(listOf(0L, 1_000_000L, 3_000_000L), local.map { it.micros })
        assertEquals(listOf(false, false, true), local.map { it.fallback })
    }

    @Test
    fun `网络目标宽度为 320，本地跟随策略宽度`() {
        val network = MediaFile(name = "电影.mkv", location = "smb://nas/电影.mkv", source = MediaSource.Network)
        val local = MediaFile(name = "电影.mkv", location = "content://media/电影.mkv")

        assertEquals(320, previewTargetWidth(network, ThumbnailPolicy(targetWidth = 512)))
        assertEquals(512, previewTargetWidth(local, ThumbnailPolicy(targetWidth = 512)))
        assertEquals(400, previewTargetWidth(local, ThumbnailPolicy(targetWidth = 400)))
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
        assertNull(chooseFrame(decoded, isSolid = { false }))
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
    fun `近黑帧不算可用封面`() {
        // 有明暗变化但整体很暗的帧能通过纯色判据，必须由暗度判据拦下。
        assertFalse(isSolidLuminance(intArrayOf(4, 20, 6, 18)))
        assertTrue(isDarkLuminance(intArrayOf(4, 20, 6, 18)))
        assertTrue(isUnusableLuminance(intArrayOf(4, 20, 6, 18)))

        assertFalse(isDarkLuminance(intArrayOf(40, 120, 80, 160)))
        assertFalse(isUnusableLuminance(intArrayOf(40, 120, 80, 160)))
    }

    @Test
    fun `纯黑帧同时按纯色与近黑处理`() {
        assertTrue(isSolidLuminance(intArrayOf(0, 0, 0)))
        assertTrue(isDarkLuminance(intArrayOf(0, 0, 0)))
        assertTrue(isUnusableLuminance(intArrayOf(0, 0, 0)))
    }

    @Test
    fun `全部候选近黑时没有可用帧`() {
        val frames = listOf(
            FramePosition(1, fallback = false) to "近黑片头",
            FramePosition(2, fallback = true) to "近黑兜底",
            FramePosition(3, fallback = false) to "近黑片尾",
        )

        assertNull(chooseFrame(frames, isSolid = { false }, isDark = { true }))
    }

    @Test
    fun `兜底帧近黑时改用其它非近黑帧`() {
        val frames = listOf(
            FramePosition(1, fallback = false) to "纯色片头",
            FramePosition(2, fallback = true) to "近黑兜底",
            FramePosition(3, fallback = false) to "正常片尾",
        )

        assertEquals("正常片尾", chooseFrame(frames, isSolid = { it == "纯色片头" }, isDark = { it == "近黑兜底" }))
    }

    @Test
    fun `网络候选近黑时退到下一个候选`() = runTest {
        val asked = mutableListOf<Long>()

        val frame = extractNetworkFrame(
            positions = networkFrameCandidates(90_000),
            decode = { micros -> asked += micros; micros },
            isSolid = { false },
            isDark = { micros -> micros != 90_000_000L },
        )

        assertEquals(90_000_000L, frame)
        assertEquals(listOf(1_000_000L, 60_000_000L, 90_000_000L), asked)
    }

    @Test
    fun `优先选第一帧非纯色`() {
        val frames = listOf(
            FramePosition(1, fallback = false) to "黑帧",
            FramePosition(2, fallback = true) to "正常帧",
            FramePosition(3, fallback = false) to "另一帧",
        )

        assertEquals("正常帧", chooseFrame(frames, isSolid = { it == "黑帧" }))
    }

    @Test
    fun `全部不合格时退回三分之一处`() {
        val frames = listOf(
            FramePosition(1, fallback = false) to "片头黑帧",
            FramePosition(2, fallback = true) to "三分之一黑帧",
            FramePosition(3, fallback = false) to "片尾黑帧",
        )

        assertEquals("三分之一黑帧", chooseFrame(frames, isSolid = { true }))
    }

    @Test
    fun `没有可用帧时返回空`() {
        assertNull(chooseFrame(emptyList<Pair<FramePosition, String>>(), isSolid = { true }))
    }

    @Test
    fun `兜底帧解码失败时回退到已解出的其它帧`() {
        val frames = listOf(
            FramePosition(1, fallback = false) to "片头纯色帧",
            FramePosition(3, fallback = false) to "片尾纯色帧",
        )

        assertEquals("片头纯色帧", chooseFrame(frames, isSolid = { true }))
    }

    @Test
    fun `候选帧全空时按抽取失败抛出`() {
        try {
            requireDecodedFrame(emptyList<Pair<FramePosition, String>>(), isSolid = { false })
            fail("应抛出抽取失败")
        } catch (expected: IOException) {
            // 预期失败路径。
        }
    }

    @Test
    fun `压缩失败时按抽取失败抛出`() {
        try {
            requireCompressedFrame(null)
            fail("应抛出压缩失败")
        } catch (expected: IOException) {
            // 预期失败路径。
        }
        assertEquals("帧", String(requireCompressedFrame("帧".toByteArray())))
    }

    @Test
    fun `封面降采样按目标宽度取二的幂`() {
        assertEquals(1, coverSampleSize(300, 320))
        assertEquals(2, coverSampleSize(1_024, 512))
        assertEquals(4, coverSampleSize(2_048, 512))
        assertEquals(1, coverSampleSize(0, 512))
        assertEquals(1, coverSampleSize(1_024, 0))
    }

    @Test
    fun `极短时长的抽帧位置仍落在时长内`() {
        val positions = framePositions(1, LOCAL_FRAME_COUNT)

        assertEquals(listOf(100L, 333L, 666L), positions.map { it.micros })
        assertTrue(positions.all { it.micros in 0L..999L })
    }

    @Test
    fun `目标宽度为零或负值按下限输出`() {
        assertEquals(320, effectiveTargetWidth(0))
        assertEquals(320, effectiveTargetWidth(-1))
        assertEquals(320, targetFrameWidth(100, targetWidth = 0))
        assertEquals(320, targetFrameWidth(100, targetWidth = -1))
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
