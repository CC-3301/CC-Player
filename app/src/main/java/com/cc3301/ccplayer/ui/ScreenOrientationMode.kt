package com.cc3301.ccplayer.ui

import android.content.pm.ActivityInfo
import com.cc3301.ccplayer.settings.OrientationMode

/**
 * 播放页要下发的系统方向模式（ADR-0013）。
 *
 * 方向交给系统的传感器旋转模式：App 不监听方向传感器、不算角度、不做迟滞与去抖。
 * 这四种模式不受系统“自动旋转”开关影响，设备接近水平时由系统忽略该角度并保持当前方向。
 *
 * 返回值是 [ActivityInfo] 的方向常量；返回 `null` 表示这次不下发，保持当前的
 * `requestedOrientation`。
 *
 * - 屏幕方向档：四向全跟；
 * - 横屏 / 竖屏档：只在各自族内跟正向与反向，物理转 90° 不跨族；
 * - 跟随视频：先按宽高比定族再取该族的传感器模式；视频比例未知期间不下发，比例到达后按它
 *   下发一次；纯音频没有视频比例，按屏幕方向档处理，不等待；
 * - 转屏覆盖：优先于档位与宽高比，离开播放页失效。
 */
fun screenOrientationMode(
    mode: OrientationMode,
    videoAspectRatio: Double?,
    rotationOverride: ScreenOrientation?,
    audioOnly: Boolean,
): Int? {
    if (mode == OrientationMode.FollowVideo) {
        // 纯音频没有视频比例，按屏幕方向档处理，不进入方向等待。
        if (audioOnly && rotationOverride == null) return ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        // 视频比例未知：这次不下发，比例到达后再按它算一次。
        if (awaitsVideoOrientation(mode, videoAspectRatio, audioOnly, rotationOverride)) return null
    }
    return systemModeOf(playerOrientation(mode, videoAspectRatio, rotationOverride))
}

/** 期望族 → 系统的传感器方向模式；反向与正向同族，取同一个模式。 */
private fun systemModeOf(orientation: ScreenOrientation): Int = when (orientation) {
    ScreenOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    ScreenOrientation.Portrait, ScreenOrientation.ReversePortrait ->
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    ScreenOrientation.Landscape, ScreenOrientation.ReverseLandscape ->
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}
