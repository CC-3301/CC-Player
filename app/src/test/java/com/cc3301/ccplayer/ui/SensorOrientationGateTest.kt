package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 传感器方向去抖：单帧跳变与回摆都不换向，用户操作后重新计数。 */
class SensorOrientationGateTest {

    private val gate = SensorOrientationGate()

    @Test
    fun `角度未知后单帧有效角度不换向`() {
        gate.onAngleUnknown()

        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.ReverseLandscape))
    }

    @Test
    fun `连续稳定达到阈值才换向`() {
        gate.onAngleUnknown()

        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
        assertTrue(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
    }

    @Test
    fun `中途回摆作废重新计数`() {
        gate.onAngleUnknown()
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))

        // 回摆到竖屏：计数从竖屏重新开始。
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Portrait))
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Portrait))
        assertTrue(gate.shouldApplyFromSensor(ScreenOrientation.Portrait))
    }

    @Test
    fun `角度再次未知作废待确认目标`() {
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))

        gate.onAngleUnknown()

        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
        assertTrue(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
    }

    @Test
    fun `用户施加后传感器帧重新计数`() {
        gate.onAngleUnknown()
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Portrait))

        // 用户点了转屏按钮：方向已立即施加，待确认计数清空。
        gate.onUserApplied()

        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
        assertFalse(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
        assertTrue(gate.shouldApplyFromSensor(ScreenOrientation.Landscape))
    }
}
