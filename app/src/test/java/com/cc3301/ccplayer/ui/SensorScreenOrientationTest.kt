package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 传感器角度 + 期望族 + 当前方向 → 播放页四向朝向，接缝 12。 */
class SensorScreenOrientationTest {

    @Test
    fun `屏幕方向档四向全跟`() {
        assertEquals(ScreenOrientation.Portrait, sensorScreenOrientation(ScreenOrientation.System, 0, null))
        assertEquals(ScreenOrientation.ReverseLandscape, sensorScreenOrientation(ScreenOrientation.System, 90, null))
        assertEquals(ScreenOrientation.ReversePortrait, sensorScreenOrientation(ScreenOrientation.System, 180, null))
        assertEquals(ScreenOrientation.Landscape, sensorScreenOrientation(ScreenOrientation.System, 270, null))
    }

    @Test
    fun `屏幕方向档按最近方向归位`() {
        listOf(0, 350, 44).forEach { angle ->
            assertEquals(ScreenOrientation.Portrait, sensorScreenOrientation(ScreenOrientation.System, angle, null))
        }
        listOf(46, 90, 134).forEach { angle ->
            assertEquals(
                ScreenOrientation.ReverseLandscape,
                sensorScreenOrientation(ScreenOrientation.System, angle, null),
            )
        }
        listOf(136, 180, 224).forEach { angle ->
            assertEquals(
                ScreenOrientation.ReversePortrait,
                sensorScreenOrientation(ScreenOrientation.System, angle, null),
            )
        }
        listOf(226, 270, 314).forEach { angle ->
            assertEquals(ScreenOrientation.Landscape, sensorScreenOrientation(ScreenOrientation.System, angle, null))
        }
    }

    @Test
    fun `横屏档只在横屏族内判正反向`() {
        (0 until 360 step 15).forEach { angle ->
            assertTrue(
                "角度 $angle 不应跨到竖屏族",
                sensorScreenOrientation(ScreenOrientation.Landscape, angle, null).isLandscape(),
            )
        }
    }

    @Test
    fun `竖屏档只在竖屏族内判正反向`() {
        (0 until 360 step 15).forEach { angle ->
            assertTrue(
                "角度 $angle 不应跨到横屏族",
                !sensorScreenOrientation(ScreenOrientation.Portrait, angle, null).isLandscape(),
            )
        }
    }

    @Test
    fun `锁定横屏时物理转 90 度不会切成竖屏`() {
        assertEquals(
            ScreenOrientation.Landscape,
            sensorScreenOrientation(ScreenOrientation.Landscape, 0, ScreenOrientation.Landscape),
        )
        assertEquals(
            ScreenOrientation.Landscape,
            sensorScreenOrientation(ScreenOrientation.Landscape, 180, ScreenOrientation.Landscape),
        )
    }

    @Test
    fun `锁定竖屏时物理转 90 度不会切成横屏`() {
        assertEquals(
            ScreenOrientation.Portrait,
            sensorScreenOrientation(ScreenOrientation.Portrait, 270, ScreenOrientation.Portrait),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            sensorScreenOrientation(ScreenOrientation.Portrait, 90, ScreenOrientation.Portrait),
        )
    }

    @Test
    fun `锁定横屏时翻到另一侧仍是横屏族且跟着翻`() {
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            sensorScreenOrientation(ScreenOrientation.Landscape, 90, ScreenOrientation.Landscape),
        )
        assertEquals(
            ScreenOrientation.Landscape,
            sensorScreenOrientation(ScreenOrientation.Landscape, 270, ScreenOrientation.ReverseLandscape),
        )
    }

    @Test
    fun `跟随视频按宽高比定族再族内判正反向`() {
        val landscape = resolvedOrientation(OrientationMode.FollowVideo, 1.78)
        val portrait = resolvedOrientation(OrientationMode.FollowVideo, 0.56)
        assertEquals(ScreenOrientation.Landscape, sensorScreenOrientation(landscape, 270, null))
        assertEquals(ScreenOrientation.Portrait, sensorScreenOrientation(portrait, 0, null))
        assertEquals(
            ScreenOrientation.ReversePortrait,
            sensorScreenOrientation(portrait, 180, ScreenOrientation.Portrait),
        )
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            sensorScreenOrientation(landscape, 90, ScreenOrientation.Landscape),
        )
    }

    @Test
    fun `跟随视频宽高比未知时按屏幕方向档处理`() {
        val unknownAspect = resolvedOrientation(OrientationMode.FollowVideo, null)
        assertEquals(ScreenOrientation.System, unknownAspect)
        assertEquals(ScreenOrientation.ReverseLandscape, sensorScreenOrientation(unknownAspect, 90, null))
        assertEquals(ScreenOrientation.ReversePortrait, sensorScreenOrientation(unknownAspect, 180, null))

        val notFinite = resolvedOrientation(OrientationMode.FollowVideo, Double.NaN)
        assertEquals(ScreenOrientation.Landscape, sensorScreenOrientation(notFinite, 270, null))
    }

    @Test
    fun `进入某方向需偏离当前超过 60 度`() {
        assertEquals(
            ScreenOrientation.Portrait,
            sensorScreenOrientation(ScreenOrientation.System, 60, ScreenOrientation.Portrait),
        )
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            sensorScreenOrientation(ScreenOrientation.System, 61, ScreenOrientation.Portrait),
        )
    }

    @Test
    fun `退回需低于 30 度`() {
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            sensorScreenOrientation(ScreenOrientation.System, 30, ScreenOrientation.ReverseLandscape),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            sensorScreenOrientation(ScreenOrientation.System, 29, ScreenOrientation.ReverseLandscape),
        )
    }

    @Test
    fun `180 度反向的迟滞边界`() {
        assertEquals(
            ScreenOrientation.Portrait,
            sensorScreenOrientation(ScreenOrientation.Portrait, 105, ScreenOrientation.Portrait),
        )
        assertEquals(
            ScreenOrientation.ReversePortrait,
            sensorScreenOrientation(ScreenOrientation.Portrait, 106, ScreenOrientation.Portrait),
        )
        assertEquals(
            ScreenOrientation.ReversePortrait,
            sensorScreenOrientation(ScreenOrientation.Portrait, 75, ScreenOrientation.ReversePortrait),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            sensorScreenOrientation(ScreenOrientation.Portrait, 74, ScreenOrientation.ReversePortrait),
        )
    }

    @Test
    fun `平放或斜放不来回跳`() {
        listOf(0, 3, 355, 2, 358, 5, 50, 55, 52, 48).forEach { angle ->
            assertEquals(
                "角度 $angle 不应换向",
                ScreenOrientation.Portrait,
                sensorScreenOrientation(ScreenOrientation.System, angle, ScreenOrientation.Portrait),
            )
        }
    }

    @Test
    fun `角度未知时保持当前方向`() {
        listOf(
            ScreenOrientation.Portrait,
            ScreenOrientation.ReversePortrait,
            ScreenOrientation.Landscape,
            ScreenOrientation.ReverseLandscape,
        ).forEach { current ->
            assertEquals(current, sensorScreenOrientation(ScreenOrientation.System, null, current))
        }
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            sensorScreenOrientation(ScreenOrientation.Landscape, null, ScreenOrientation.ReverseLandscape),
        )
    }

    @Test
    fun `角度未知且当前方向不属于期望族时取族正向`() {
        assertEquals(
            ScreenOrientation.Landscape,
            sensorScreenOrientation(ScreenOrientation.Landscape, null, ScreenOrientation.ReversePortrait),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            sensorScreenOrientation(ScreenOrientation.Portrait, null, ScreenOrientation.ReverseLandscape),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            sensorScreenOrientation(ScreenOrientation.System, null, null),
        )
    }

    @Test
    fun `进入播放页尚未收到角度时按期望族正向施加`() {
        assertEquals(ScreenOrientation.Landscape, sensorScreenOrientation(ScreenOrientation.Landscape, null, null))
        assertEquals(ScreenOrientation.Portrait, sensorScreenOrientation(ScreenOrientation.Portrait, null, null))
    }
}
