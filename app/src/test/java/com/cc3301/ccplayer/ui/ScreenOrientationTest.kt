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

    @Test
    fun `转屏按钮按当前横竖状态切到另一个方向`() {
        assertEquals(ScreenOrientation.Portrait, toggledOrientation(isLandscape = true))
        assertEquals(ScreenOrientation.Landscape, toggledOrientation(isLandscape = false))
    }

    @Test
    fun `本次播放的转屏结果优先于方向设置`() {
        assertEquals(
            ScreenOrientation.Portrait,
            playerOrientation(OrientationMode.Landscape, 1.78, ScreenOrientation.Portrait),
        )
        assertEquals(
            ScreenOrientation.Landscape,
            playerOrientation(OrientationMode.Portrait, 0.56, ScreenOrientation.Landscape),
        )
    }

    @Test
    fun `跟随视频模式下本次播放的转屏结果仍然生效`() {
        assertEquals(
            ScreenOrientation.Portrait,
            playerOrientation(OrientationMode.FollowVideo, 1.78, ScreenOrientation.Portrait),
        )
        assertEquals(
            ScreenOrientation.Landscape,
            playerOrientation(OrientationMode.FollowVideo, 0.56, ScreenOrientation.Landscape),
        )
    }

    @Test
    fun `没有转屏结果时按设置判定`() {
        assertEquals(
            ScreenOrientation.Landscape,
            playerOrientation(OrientationMode.Landscape, null, null),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            playerOrientation(OrientationMode.FollowVideo, 0.56, null),
        )
    }
}
