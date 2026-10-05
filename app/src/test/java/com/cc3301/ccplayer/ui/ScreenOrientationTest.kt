package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOrientationTest {

    @Test
    fun `屏幕方向模式交给系统决定`() {
        assertEquals(ScreenOrientation.System, resolvedOrientation(OrientationMode.System, 1.78))
        assertEquals(ScreenOrientation.System, resolvedOrientation(OrientationMode.System, null))
    }

    @Test
    fun `固定模式不看视频比例`() {
        listOf(2.4, 1.0, 0.56, null).forEach { aspect ->
            assertEquals(ScreenOrientation.Landscape, resolvedOrientation(OrientationMode.Landscape, aspect))
            assertEquals(ScreenOrientation.Portrait, resolvedOrientation(OrientationMode.Portrait, aspect))
        }
    }

    @Test
    fun `跟随视频宽高比大于等于 1 取横屏`() {
        assertEquals(ScreenOrientation.Landscape, resolvedOrientation(OrientationMode.FollowVideo, 1.0))
        assertEquals(ScreenOrientation.Landscape, resolvedOrientation(OrientationMode.FollowVideo, 1.78))
        assertEquals(ScreenOrientation.Landscape, resolvedOrientation(OrientationMode.FollowVideo, 2.39))
    }

    @Test
    fun `跟随视频宽高比小于 1 取竖屏`() {
        assertEquals(ScreenOrientation.Portrait, resolvedOrientation(OrientationMode.FollowVideo, 0.99))
        assertEquals(ScreenOrientation.Portrait, resolvedOrientation(OrientationMode.FollowVideo, 0.5625))
    }

    @Test
    fun `跟随视频比例未知时交给系统决定`() {
        assertEquals(ScreenOrientation.System, resolvedOrientation(OrientationMode.FollowVideo, null))
    }

    @Test
    fun `跟随视频比例非正或非有限时交给系统决定`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { aspect ->
            assertEquals(ScreenOrientation.System, resolvedOrientation(OrientationMode.FollowVideo, aspect))
        }
    }
}
