package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLevelControlsTest {

    private val volume = FakeVolumeControl(max = 15, current = 0)
    private val levels = PlayerLevelControls(volume)

    @Test
    fun `上滑增大音量并写到档位`() {
        volume.current = 3

        val shown = levels.dragVolume(dragAmount = -200f, viewHeight = 1_000)

        assertEquals(0.4f, shown, 0.0001f)
        assertEquals(listOf(6), volume.written)
    }

    @Test
    fun `下滑减小音量`() {
        volume.current = 6

        val shown = levels.dragVolume(dragAmount = 200f, viewHeight = 1_000)

        assertEquals(0.2f, shown, 0.0001f)
        assertEquals(listOf(3), volume.written)
    }

    @Test
    fun `滑过整屏高度等于满量程`() {
        volume.current = 0

        val shown = levels.dragVolume(dragAmount = -1_000f, viewHeight = 1_000)

        assertEquals(1f, shown, 0.0001f)
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
        volume.current = 3

        val shown = levels.dragVolume(dragAmount = -100f, viewHeight = 0)

        assertEquals(0.2f, shown, 0.0001f)
        assertEquals(listOf(3), volume.written)
    }

    @Test
    fun `满档为零时不写入`() {
        val muted = FakeVolumeControl(max = 0, current = 0)
        val controls = PlayerLevelControls(muted)

        val shown = controls.dragVolume(dragAmount = -100f, viewHeight = 1_000)

        assertEquals(0f, shown, 0.0001f)
        assertEquals(emptyList<Int>(), muted.written)
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
