package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首次摆位门：只有横向视频落在竖屏上才等方向，且一轮只结一次案。 */
class PlayerPlacementGateTest {

    @Test
    fun `比例先到首帧后到时仍等方向，对齐后才撤黑`() {
        // 比例先到，首帧还没到：还没开始等（黑底由没画面负责）。
        var gate = PlayerPlacementGate().withFrame(
            ratioKnown = true,
            showsPicture = false,
            rotationPending = true,
        )
        assertFalse(gate.awaitsOrientation(ratioKnown = true, showsPicture = false, rotationPending = true))

        // 首帧后到：黑底继续盖着。
        gate = gate.withFrame(ratioKnown = true, showsPicture = true, rotationPending = true)
        assertTrue(gate.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = true))
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = 16.0 / 9.0,
                aspectRatioPlaced = gate.settled,
                orientationPending = gate.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = true),
            ),
        )

        // 方向对齐：同一次里撤黑。
        val aligned = gate.withFrame(ratioKnown = true, showsPicture = true, rotationPending = false)
        assertTrue(aligned.settled)
        assertFalse(aligned.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = false))
        assertFalse(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = 16.0 / 9.0,
                aspectRatioPlaced = aligned.settled,
                orientationPending = aligned.awaitsOrientation(
                    ratioKnown = true,
                    showsPicture = true,
                    rotationPending = false,
                ),
            ),
        )
    }

    @Test
    fun `方向回摆不重新盖黑也不重开计时`() {
        val settled = PlayerPlacementGate()
            .withFrame(ratioKnown = true, showsPicture = true, rotationPending = true)
            .withFrame(ratioKnown = true, showsPicture = true, rotationPending = false)

        assertTrue(settled.settled)
        // 方向又摆回去：不再等、不再盖黑。
        val wobbled = settled.withFrame(ratioKnown = true, showsPicture = true, rotationPending = true)
        assertFalse(wobbled.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = true))
    }

    @Test
    fun `等待中首帧短暂消失不重开计时`() {
        val waiting = PlayerPlacementGate()
            .withFrame(ratioKnown = true, showsPicture = true, rotationPending = true)
            .withFrame(ratioKnown = true, showsPicture = false, rotationPending = true)

        assertEquals(PlayerPlacementPhase.Waiting, waiting.phase)
        // 仍按“还在等”处理：计时键不翻，2 秒不会从这一刻重新开始。
        assertTrue(waiting.awaitsOrientation(ratioKnown = true, showsPicture = false, rotationPending = true))
    }

    @Test
    fun `等待中比例丢失不算方向对齐`() {
        val waiting = PlayerPlacementGate()
            .withFrame(ratioKnown = true, showsPicture = true, rotationPending = true)
            .withFrame(ratioKnown = false, showsPicture = false, rotationPending = false)

        assertEquals(PlayerPlacementPhase.Waiting, waiting.phase)
    }

    @Test
    fun `竖向视频与已对齐的屏幕不等方向`() {
        val portraitOnLandscape = PlayerPlacementGate().withFrame(
            ratioKnown = true,
            showsPicture = true,
            rotationPending = false,
        )

        assertFalse(portraitOnLandscape.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = false))
        assertTrue(portraitOnLandscape.settled)
    }

    @Test
    fun `比例未知时不等方向`() {
        val gate = PlayerPlacementGate().withFrame(
            ratioKnown = false,
            showsPicture = true,
            rotationPending = false,
        )

        assertFalse(gate.awaitsOrientation(ratioKnown = false, showsPicture = true, rotationPending = false))
        assertFalse(gate.settled)
    }

    @Test
    fun `结案后切集不再等方向`() {
        val settled = PlayerPlacementGate().withFrame(
            ratioKnown = true,
            showsPicture = true,
            rotationPending = false,
        )

        assertFalse(settled.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = true))
    }

    @Test
    fun `时限只在等待中生效`() {
        // 没开始等时收到到时限：不结案，随后仍按事实开始等待。
        val idle = PlayerPlacementGate().withWaitLimit()

        assertFalse(idle.settled)
        assertTrue(idle.awaitsOrientation(ratioKnown = true, showsPicture = true, rotationPending = true))
    }

    @Test
    fun `只有横向视频落在竖屏上才等方向`() {
        assertTrue(
            awaitsVideoRotation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 16.0 / 9.0,
                screenOrientation = ScreenOrientation.Portrait,
                rotationOverride = null,
            ),
        )
        // 竖向视频落在横屏上：不等。
        assertFalse(
            awaitsVideoRotation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 9.0 / 16.0,
                screenOrientation = ScreenOrientation.Landscape,
                rotationOverride = null,
            ),
        )
        // 屏幕已经是横屏：不等。
        assertFalse(
            awaitsVideoRotation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 16.0 / 9.0,
                screenOrientation = ScreenOrientation.Landscape,
                rotationOverride = null,
            ),
        )
        // 比例未知：由比例判据盖黑，不进等待。
        assertFalse(
            awaitsVideoRotation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = null,
                screenOrientation = ScreenOrientation.Portrait,
                rotationOverride = null,
            ),
        )
    }

    @Test
    fun `固定档位与手动转屏不等方向`() {
        listOf(OrientationMode.System, OrientationMode.Landscape, OrientationMode.Portrait).forEach { mode ->
            assertFalse(
                awaitsVideoRotation(
                    mode = mode,
                    videoAspectRatio = 16.0 / 9.0,
                    screenOrientation = ScreenOrientation.Portrait,
                    rotationOverride = null,
                ),
            )
        }
        // 跟随视频下把横向视频按成竖屏：屏幕不会转成横向，不等。
        assertFalse(
            awaitsVideoRotation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 16.0 / 9.0,
                screenOrientation = ScreenOrientation.Portrait,
                rotationOverride = ScreenOrientation.Portrait,
            ),
        )
    }
}
