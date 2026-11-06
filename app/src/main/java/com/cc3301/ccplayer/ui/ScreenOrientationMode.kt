package com.cc3301.ccplayer.ui

import android.content.pm.ActivityInfo
import com.cc3301.ccplayer.settings.OrientationMode

/**
 * 播放页要下发的系统方向模式（ADR-0013）。
 *
 * 方向交给系统的传感器旋转模式：App 不监听方向传感器、不算角度、不做迟滞与去抖。
 * 这四种模式不受系统“自动旋转”开关影响，设备接近水平时由系统忽略该角度并保持当前方向；
 * 180° 反向是否生效由系统的 `allowAllRotations` 决定，App 不保证。
 *
 * [mode] 为空表示不在播放页，方向交回系统。
 *
 * - 屏幕方向档：四向全跟；
 * - 横屏 / 竖屏档：只在各自族内跟传感器的正反向，物理转 90° 不跨族；
 * - 跟随视频：比例已知时先按宽高比定族再取该族的传感器模式；视频比例未知期间锁住当前方向，
 *   不停在系统的自动旋转上；纯音频与无视频轨的条目按屏幕方向档处理，不等待；
 * - 转屏覆盖：优先于档位与宽高比，离开播放页失效。
 */
fun screenOrientationMode(
    mode: OrientationMode?,
    videoAspectRatio: Double?,
    rotationOverride: ScreenOrientation?,
    audioOnly: Boolean,
): Int {
    if (mode == null) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    if (mode == OrientationMode.FollowVideo) {
        // 没有视频方向的条目按屏幕方向档处理，不进入方向等待。
        if (audioOnly && rotationOverride == null) return ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        if (awaitsVideoOrientation(mode, videoAspectRatio, audioOnly, rotationOverride)) {
            // 视频比例未知：锁住当前方向，不把方向交回系统去跟自动旋转。
            return ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
    }
    return systemModeOf(playerOrientation(mode, videoAspectRatio, rotationOverride))
}

/** 期望族 → 系统的传感器方向模式。 */
private fun systemModeOf(orientation: ScreenOrientation): Int = when (orientation) {
    ScreenOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    ScreenOrientation.Portrait, ScreenOrientation.ReversePortrait ->
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    ScreenOrientation.Landscape, ScreenOrientation.ReverseLandscape ->
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
