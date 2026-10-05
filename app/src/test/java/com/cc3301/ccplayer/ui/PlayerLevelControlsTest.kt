package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLevelControlsTest {

    private val brightness = FakeBrightnessControl()
    private val volume = FakeVolumeControl(max = 15, current = 0)
    private val levels = PlayerLevelControls(brightness, volume)

    @Test
    fun `上滑增大亮度`() {
        brightness.current = 0.4f

        val shown = levels.dragBrightness(dragAmount = -100f, viewHeight = 1_000)

        assertEquals(0.5f, shown, 0.0001f)
        assertEquals(listOf(0.5f), brightness.written)
    }

    @Test
    fun `下滑减小亮度`() {
        brightness.current = 0.4f

        val shown = levels.dragBrightness(dragAmount = 100f, viewHeight = 1_000)

        assertEquals(0.3f, shown, 0.0001f)
        assertEquals(listOf(0.3f), brightness.written)
    }

    @Test
    fun `滑过整屏高度等于满量程`() {
        brightness.current = 0f

        val shown = levels.dragBrightness(dragAmount = -1_000f, viewHeight = 1_000)

        assertEquals(1f, shown, 0.0001f)
    }

    @Test
    fun `亮度夹取在 0 到 1`() {
        brightness.current = 0.9f
        levels.dragBrightness(dragAmount = -1_000f, viewHeight = 1_000)

        brightness.current = 0.1f
        levels.dragBrightness(dragAmount = 1_000f, viewHeight = 1_000)

        assertEquals(listOf(1f, 0f), brightness.written)
    }

    @Test
    fun `上滑增大音量并写到档位`() {
        volume.current = 3

        val shown = levels.dragVolume(dragAmount = -200f, viewHeight = 1_000)

        assertEquals(0.4f, shown, 0.0001f)
        assertEquals(listOf(6), volume.written)
    }

    @Test
    fun `音量夹取在 0 到满档`() {
        volume.current = 14
        levels.dragVolume(dragAmount = -1_000f, viewHeight = 1_000)

        volume.current = 1
        levels.dragVolume(dragAmount = 1_000f, viewHeight = 1_000)

        assertEquals(listOf(15, 0), volume.written)
    }

    @Test
    fun `小幅拖动逐次累积不被档位取整吃掉`() {
        volume.current = 0

        repeat(5) { levels.dragVolume(dragAmount = -10f, viewHeight = 1_000) }

        assertEquals(1, volume.written.last())
    }

    @Test
    fun `屏高为零时不改变取值`() {
        brightness.current = 0.4f

        val shown = levels.dragBrightness(dragAmount = -100f, viewHeight = 0)

        assertEquals(0.4f, shown, 0.0001f)
        assertEquals(listOf(0.4f), brightness.written)
    }

    private class FakeBrightnessControl(var current: Float = 0.5f) : BrightnessControl {
        val written = mutableListOf<Float>()

        override fun read(): Float = current

        override fun write(fraction: Float) {
            written += fraction
            current = fraction
        }
    }

    private class FakeVolumeControl(private val max: Int, var current: Int) : VolumeControl {
        val written = mutableListOf<Int>()

        override val maxVolume: Int get() = max

        override fun read(): Int = current

        override fun write(volume: Int) {
            written += volume
            current = volume
        }
    }
}
