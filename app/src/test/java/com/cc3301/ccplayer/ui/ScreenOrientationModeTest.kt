package com.cc3301.ccplayer.ui

import android.content.pm.ActivityInfo
import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 方向档位 + 视频宽高比 + 转屏覆盖 + 有无视频方向 → 下发的系统方向模式，接缝 12。
 *
 * 方向交给系统的传感器旋转模式，App 不再自己判角度；180° 反向由系统的 `allowAllRotations`
 * 决定，这里不断言反向。
 */
class ScreenOrientationModeTest {

    private fun mode(
        mode: OrientationMode?,
        ratio: Double? = null,
        override: ScreenOrientation? = null,
        audioOnly: Boolean = false,
    ): Int = screenOrientationMode(mode, ratio, override, audioOnly)

    @Test
    fun `四个档位各取对应的系统传感器模式`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            mode(OrientationMode.System, 1.78),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            mode(OrientationMode.Landscape, 0.5625),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            mode(OrientationMode.Portrait, 1.78),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            mode(OrientationMode.FollowVideo, 1.78),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            mode(OrientationMode.FollowVideo, 0.5625),
        )
    }

    @Test
    fun `跟随视频按宽高比在横竖族之间切换`() {
        listOf(2.39, 1.0).forEach { ratio ->
            assertEquals(
                "比例 $ratio",
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                mode(OrientationMode.FollowVideo, ratio),
            )
        }
        listOf(0.99, 0.5625).forEach { ratio ->
            assertEquals(
                "比例 $ratio",
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                mode(OrientationMode.FollowVideo, ratio),
            )
        }
    }

    @Test
    fun `转屏覆盖优先于档位与宽高比`() {
        // 竖屏档 + 横向视频，点过转屏按钮后按横屏族下发。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            mode(OrientationMode.Portrait, 1.78, ScreenOrientation.Landscape),
        )
        // 横屏档 + 竖向视频，点过转屏按钮后按竖屏族下发。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            mode(OrientationMode.Landscape, 0.5625, ScreenOrientation.Portrait),
        )
    }

    @Test
    fun `跟随视频比例未知时锁住当前方向`() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { ratio ->
            assertEquals(
                "比例 $ratio",
                ActivityInfo.SCREEN_ORIENTATION_LOCKED,
                mode(OrientationMode.FollowVideo, ratio),
            )
        }
    }

    @Test
    fun `比例未知时转屏覆盖照样下发`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            mode(OrientationMode.FollowVideo, null, ScreenOrientation.Landscape),
        )
    }

    @Test
    fun `没有视频方向的条目按屏幕方向档处理`() {
        // 扩展名是音频，或容器里没有视频轨：都不进入方向等待。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            mode(OrientationMode.FollowVideo, null, audioOnly = true),
        )
        // 比例已知也不按比例分族，照样按屏幕方向档。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            mode(OrientationMode.FollowVideo, 1.78, audioOnly = true),
        )
    }

    @Test
    fun `固定档位不看比例`() {
        listOf(null, 2.39, 0.5625).forEach { ratio ->
            assertEquals(
                "比例 $ratio",
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                mode(OrientationMode.Landscape, ratio),
            )
            assertEquals(
                "比例 $ratio",
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                mode(OrientationMode.Portrait, ratio),
            )
            assertEquals(
                "比例 $ratio",
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                mode(OrientationMode.System, ratio),
            )
        }
    }

    @Test
    fun `离开播放页交回系统`() {
        listOf(null, 2.39, 0.5625).forEach { ratio ->
            assertEquals(
                "比例 $ratio",
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                mode(null, ratio),
            )
        }
        // 有转屏覆盖也照旧交回系统。
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            mode(null, 1.78, ScreenOrientation.Landscape),
        )
    }
}
