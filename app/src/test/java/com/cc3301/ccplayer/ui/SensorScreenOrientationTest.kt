package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传感器角度 + 期望族 + 已施加方向 → 播放页要施加的朝向，接缝 12。
 *
 * 迟滞按步进区分：相隔 90° 的相邻档位为进入 61°、退回 29°；锁定族内相隔 180° 的一对为
 * 106° 进、74° 退。角度未知时只施加族正向。
 */
class SensorScreenOrientationTest {

    private fun decide(
        requested: ScreenOrientation,
        angle: Int?,
        current: ScreenOrientation = ScreenOrientation.System,
    ): ScreenOrientation = sensorScreenOrientation(requested, angle, current)

    @Test
    fun `角度未知时四个档位按族正向施加`() {
        // 屏幕方向档交回系统，不锁竖屏。
        assertEquals(ScreenOrientation.System, decide(ScreenOrientation.System, null))
        // 横屏档与竖屏档施加各自族正向。
        assertEquals(ScreenOrientation.Landscape, decide(ScreenOrientation.Landscape, null))
        assertEquals(ScreenOrientation.Portrait, decide(ScreenOrientation.Portrait, null))
        // 跟随视频先按宽高比定族，再施加族正向。
        assertEquals(
            ScreenOrientation.Landscape,
            decide(resolvedOrientation(OrientationMode.FollowVideo, 1.78), null),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            decide(resolvedOrientation(OrientationMode.FollowVideo, 0.56), null),
        )
        // 跟随视频比例未知时按屏幕方向档处理。
        assertEquals(ScreenOrientation.System, decide(resolvedOrientation(OrientationMode.FollowVideo, null), null))
    }

    @Test
    fun `角度未知时转屏按钮的覆盖立即生效`() {
        val session = PlayerSessionOrientation()
        // 横屏档、角度未知：先按档位施加横屏正向。
        assertEquals(
            ScreenOrientation.Landscape,
            decide(session.resolve(OrientationMode.Landscape, null), null),
        )
        // 点一次转屏按钮：覆盖竖屏族，角度仍未知也立即施加竖屏正向。
        session.toggle(ScreenOrientation.Landscape)
        assertEquals(
            ScreenOrientation.Portrait,
            decide(session.resolve(OrientationMode.Landscape, null), null),
        )
        // 跟随视频档的族仍由视频宽高比决定，转屏按钮只覆盖族。
        session.toggle(ScreenOrientation.Portrait)
        assertEquals(
            ScreenOrientation.Landscape,
            decide(session.resolve(OrientationMode.FollowVideo, 0.56), null),
        )
    }

    @Test
    fun `角度未知时保留已施加的族内反向`() {
        // 支架上角度未知，此前传感器已施加反向横屏：切集等重算不翻回正向。
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            decide(ScreenOrientation.Landscape, null, ScreenOrientation.ReverseLandscape),
        )
        assertEquals(
            ScreenOrientation.ReversePortrait,
            decide(ScreenOrientation.Portrait, null, ScreenOrientation.ReversePortrait),
        )
        // 已施加方向不属于新的期望族时回到该族正向。
        assertEquals(
            ScreenOrientation.Landscape,
            decide(ScreenOrientation.Landscape, null, ScreenOrientation.ReversePortrait),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            decide(ScreenOrientation.Portrait, null, ScreenOrientation.ReverseLandscape),
        )
    }

    @Test
    fun `角度未知后收到角度仍按族内迟滞`() {
        // 未知时施加横屏正向，角度落在正向附近保持，越过 106° 才换反向横屏。
        assertEquals(ScreenOrientation.Landscape, decide(ScreenOrientation.Landscape, null))
        assertEquals(
            ScreenOrientation.Landscape,
            decide(ScreenOrientation.Landscape, 300, ScreenOrientation.Landscape),
        )
        assertEquals(
            ScreenOrientation.ReverseLandscape,
            decide(ScreenOrientation.Landscape, 16, ScreenOrientation.Landscape),
        )
    }

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
