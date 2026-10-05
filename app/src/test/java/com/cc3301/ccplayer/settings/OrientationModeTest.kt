package com.cc3301.ccplayer.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationModeTest {

    @Test
    fun `控制条按钮按屏幕方向、横屏、竖屏、跟随视频轮换`() {
        assertEquals(OrientationMode.Landscape, OrientationMode.System.next())
        assertEquals(OrientationMode.Portrait, OrientationMode.Landscape.next())
        assertEquals(OrientationMode.FollowVideo, OrientationMode.Portrait.next())
        assertEquals(OrientationMode.System, OrientationMode.FollowVideo.next())
    }

    @Test
    fun `轮换四档回到起点`() {
        var mode = OrientationMode.System

        repeat(OrientationMode.entries.size) { mode = mode.next() }

        assertEquals(OrientationMode.System, mode)
    }
}
