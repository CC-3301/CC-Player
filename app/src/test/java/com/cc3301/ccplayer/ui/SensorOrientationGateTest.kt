package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 传感器方向去抖：单帧跳变与回摆都不换向，用户操作直接施加。 */
class SensorOrientationGateTest {

    private val gate = SensorOrientationGate()

    @Test
    fun `角度未知后单帧有效角度不换向`() {
        gate.onAngleUnknown()

        assertFalse(gate.allowApply(ScreenOrientation.ReverseLandscape, immediate = false))
    }

    @Test
    fun `连续三帧同一方向才换向`() {
        gate.onAngleUnknown()

        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
        assertTrue(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
    }

    @Test
    fun `中途回摆作废重新计数`() {
        gate.onAngleUnknown()
        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))

        // 回摆到竖屏：计数从竖屏重新开始。
        assertFalse(gate.allowApply(ScreenOrientation.Portrait, immediate = false))
        assertFalse(gate.allowApply(ScreenOrientation.Portrait, immediate = false))
        assertTrue(gate.allowApply(ScreenOrientation.Portrait, immediate = false))
    }

    @Test
    fun `角度再次未知作废待确认目标`() {
        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))

        gate.onAngleUnknown()

        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
        assertTrue(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
    }

    @Test
    fun `用户操作立即施加并重新计数`() {
        gate.onAngleUnknown()
        assertFalse(gate.allowApply(ScreenOrientation.Portrait, immediate = false))

        // 点转屏按钮：目标变成横屏，立即施加。
        assertTrue(gate.allowApply(ScreenOrientation.Landscape, immediate = true))

        // 之后的传感器帧重新从第一帧计数。
        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
        assertFalse(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
        assertTrue(gate.allowApply(ScreenOrientation.Landscape, immediate = false))
    }
}
