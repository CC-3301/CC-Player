package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传感器角度 + 期望族 + 已施加方向 → 播放页四向朝向，接缝 12。
 *
 * 迟滞按步进区分：相隔 90° 的相邻档位为进入 61°、退回 29°；锁定族内相隔 180° 的一对为
 * 106° 进、74° 退。
 */
class SensorScreenOrientationTest {

    private fun decide(
        requested: ScreenOrientation,
        angle: Int,
        current: ScreenOrientation = ScreenOrientation.System,
    ): ScreenOrientation = sensorScreenOrientation(requested, angle, current)

    @Test
    fun `四向档四向全跟`() {
        assertEquals(ScreenOrientation.Portrait, decide(ScreenOrientation.System, 0))
        assertEquals(ScreenOrientation.ReverseLandscape, decide(ScreenOrientation.System, 90))
        assertEquals(ScreenOrientation.ReversePortrait, decide(ScreenOrientation.System, 180))
        assertEquals(ScreenOrientation.Landscape, decide(ScreenOrientation.System, 270))
    }

    @Test
    fun `四向档连续转动逐档跟手`() {
        var current = ScreenOrientation.System
        val steps = listOf(
            0 to ScreenOrientation.Portrait,
            45 to ScreenOrientation.Portrait,
            60 to ScreenOrientation.Portrait,
            61 to ScreenOrientation.ReverseLandscape,
            90 to ScreenOrientation.ReverseLandscape,
            135 to ScreenOrientation.ReverseLandscape,
            150 to ScreenOrientation.ReverseLandscape,
            151 to ScreenOrientation.ReversePortrait,
            180 to ScreenOrientation.ReversePortrait,
            210 to ScreenOrientation.ReversePortrait,
            240 to ScreenOrientation.ReversePortrait,
            270 to ScreenOrientation.Landscape,
            300 to ScreenOrientation.Landscape,
            330 to ScreenOrientation.Landscape,
            332 to ScreenOrientation.Portrait,
            350 to ScreenOrientation.Portrait,
        )
        steps.forEach { (angle, expected) ->
            current = decide(ScreenOrientation.System, angle, current)
            assertEquals("角度 $angle", expected, current)
        }
    }

    @Test
    fun `首次判定从期望族正向起步`() {
        // 屏幕方向档从竖屏正向起步：46° 与 59° 都还没越过迟滞门槛。
        assertEquals(ScreenOrientation.Portrait, decide(ScreenOrientation.System, 46))
        assertEquals(ScreenOrientation.Portrait, decide(ScreenOrientation.System, 59))
        assertEquals(ScreenOrientation.ReverseLandscape, decide(ScreenOrientation.System, 61))
        // 锁定横屏档首次施加取横屏正向，竖屏角度不跨族。
        assertEquals(ScreenOrientation.Landscape, decide(ScreenOrientation.Landscape, 0))
        assertEquals(ScreenOrientation.Landscape, decide(ScreenOrientation.Landscape, 270))
        // 锁定竖屏档首次施加取竖屏正向。
        assertEquals(ScreenOrientation.Portrait, decide(ScreenOrientation.Portrait, 0))
        assertEquals(ScreenOrientation.Portrait, decide(ScreenOrientation.Portrait, 300))
    }

    @Test
    fun `相邻 90 度步进的迟滞边界`() {
        // 进入需偏离当前超过 60°：60° 不动，61° 才进。
        assertEquals(ScreenOrientation.Portrait, decide(ScreenOrientation.System, 60, ScreenOrientation.Portrait))
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            decide(ScreenOrientation.System, 61, ScreenOrientation.Portrait),
        )
        // 退回需低于 30°：30° 不动，29° 才退。
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            decide(ScreenOrientation.System, 30, ScreenOrientation.ReverseLandscape),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            decide(ScreenOrientation.System, 29, ScreenOrientation.ReverseLandscape),
        )
    }

    @Test
    fun `锁定族内 180 度对的迟滞边界`() {
        // 竖屏 ↔ 反向竖屏：106° 进、74° 退。
        assertEquals(ScreenOrientation.Portrait, decide(ScreenOrientation.Portrait, 105, ScreenOrientation.Portrait))
        assertEquals(
            ScreenOrientation.ReversePortrait,
            decide(ScreenOrientation.Portrait, 106, ScreenOrientation.Portrait),
        )
        assertEquals(
            ScreenOrientation.ReversePortrait,
            decide(ScreenOrientation.Portrait, 75, ScreenOrientation.ReversePortrait),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            decide(ScreenOrientation.Portrait, 74, ScreenOrientation.ReversePortrait),
        )
        // 横屏 ↔ 反向横屏：横屏正向在 270°，同样 106° 进、74° 退。
        assertEquals(ScreenOrientation.Landscape, decide(ScreenOrientation.Landscape, 15, ScreenOrientation.Landscape))
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            decide(ScreenOrientation.Landscape, 16, ScreenOrientation.Landscape),
        )
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            decide(ScreenOrientation.Landscape, 345, ScreenOrientation.ReverseLandscape),
        )
        assertEquals(
            ScreenOrientation.Landscape,
            decide(ScreenOrientation.Landscape, 344, ScreenOrientation.ReverseLandscape),
        )
    }

    @Test
    fun `锁定横屏时物理转 90 度不会切成竖屏`() {
        (0 until 360 step 15).forEach { angle ->
            assertTrue(
                "角度 $angle 不应跨到竖屏族",
                decide(ScreenOrientation.Landscape, angle, ScreenOrientation.Landscape).isLandscape(),
            )
        }
    }

    @Test
    fun `锁定竖屏时物理转 90 度不会切成横屏`() {
        (0 until 360 step 15).forEach { angle ->
            assertTrue(
                "角度 $angle 不应跨到横屏族",
                !decide(ScreenOrientation.Portrait, angle, ScreenOrientation.Portrait).isLandscape(),
            )
        }
    }

    @Test
    fun `锁定横屏时翻到另一侧跟着翻且仍是横屏族`() {
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            decide(ScreenOrientation.Landscape, 90, ScreenOrientation.Landscape),
        )
        assertEquals(
            ScreenOrientation.Landscape,
            decide(ScreenOrientation.Landscape, 270, ScreenOrientation.ReverseLandscape),
        )
    }

    @Test
    fun `跟随视频按宽高比定族再族内判正反向`() {
        val landscape = resolvedOrientation(OrientationMode.FollowVideo, 1.78)
        val portrait = resolvedOrientation(OrientationMode.FollowVideo, 0.56)
        assertEquals(ScreenOrientation.Landscape, decide(landscape, 270))
        assertEquals(ScreenOrientation.Landscape, decide(landscape, 0))
        assertEquals(ScreenOrientation.Portrait, decide(portrait, 0))
        assertEquals(ScreenOrientation.Portrait, decide(portrait, 270))
        assertEquals(ScreenOrientation.ReversePortrait, decide(portrait, 180, ScreenOrientation.Portrait))
        assertEquals(ScreenOrientation.ReverseLandscape, decide(landscape, 90, ScreenOrientation.Landscape))
    }

    @Test
    fun `跟随视频宽高比未知时按屏幕方向档处理`() {
        val unknownAspect = resolvedOrientation(OrientationMode.FollowVideo, null)
        assertEquals(ScreenOrientation.System, unknownAspect)
        assertEquals(ScreenOrientation.ReverseLandscape, decide(unknownAspect, 90))
        assertEquals(ScreenOrientation.ReversePortrait, decide(unknownAspect, 180))

        val notFinite = resolvedOrientation(OrientationMode.FollowVideo, Double.NaN)
        assertEquals(ScreenOrientation.System, notFinite)
        assertEquals(ScreenOrientation.Landscape, decide(notFinite, 270))
    }

    @Test
    fun `斜放抖动不来回跳`() {
        var current = ScreenOrientation.Portrait
        listOf(0, 3, 355, 2, 358, 5, 50, 55, 52, 48, 59).forEach { angle ->
            current = decide(ScreenOrientation.System, angle, current)
            assertEquals("角度 $angle 不应换向", ScreenOrientation.Portrait, current)
        }
    }
}
