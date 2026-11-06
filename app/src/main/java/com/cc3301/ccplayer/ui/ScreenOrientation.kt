package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode

/**
 * 屏幕朝向。`Portrait` / `ReversePortrait` 为竖屏族正向与反向，`Landscape` / `ReverseLandscape` 为
 * 横屏族正向与反向。
 *
 * `System` 表示四向全跟，它是屏幕方向档的期望族；跟随视频比例未知时不下发它，而是锁住当前方向。
 * 反向与正向同属一个族，只影响屏幕实际朝向，不改变下发的系统方向模式。
 */
enum class ScreenOrientation { System, Portrait, ReversePortrait, Landscape, ReverseLandscape }

/** 是否属于横屏族。 */
fun ScreenOrientation.isLandscape(): Boolean =
    this == ScreenOrientation.Landscape || this == ScreenOrientation.ReverseLandscape

/**
 * 按方向设置与视频宽高比判定期望的族（ADR-0013）。
 *
 * 跟随视频：宽高比 ≥1 取横屏族，<1 取竖屏族。
 *
 * 比例未知、非正或非有限时返回 [ScreenOrientation.System]，只给「首次撤黑等方向」
 * （[awaitsVideoRotation]）这类调用方兜底：播放页下发不走这个分支，同样条件下
 * [screenOrientationMode] 判成锁住当前方向。
 */
fun resolvedOrientation(mode: OrientationMode, videoAspectRatio: Double?): ScreenOrientation =
    when (mode) {
        OrientationMode.System -> ScreenOrientation.System
        OrientationMode.Landscape -> ScreenOrientation.Landscape
        OrientationMode.Portrait -> ScreenOrientation.Portrait
        OrientationMode.FollowVideo -> when {
            !hasKnownVideoAspectRatio(videoAspectRatio) -> ScreenOrientation.System
            videoAspectRatio != null && videoAspectRatio >= 1.0 -> ScreenOrientation.Landscape
            else -> ScreenOrientation.Portrait
        }
    }

/** 播放页朝向：本次播放的转屏结果始终优先（包括跟随视频），没有时按设置判定（ADR-0025）。 */
fun playerOrientation(
    mode: OrientationMode,
    videoAspectRatio: Double?,
    rotationOverride: ScreenOrientation?,
): ScreenOrientation = rotationOverride ?: resolvedOrientation(mode, videoAspectRatio)

/**
 * 这次首次撤黑要不要等屏幕转过来。
 *
 * 只有“跟随视频”档、视频本身是横向（宽高比 ≥1）、当前屏幕还是竖向，且本次播放要施加的方向
 * 也是横向时才算。竖向视频、屏幕已经同族、固定档位一律不等：竖向视频落在横屏上直接按原逻辑
 * 撤黑，没有这道等待。转屏按钮把画面按成竖屏时屏幕不会转过去，也不算等待。
 */
fun awaitsVideoRotation(
    mode: OrientationMode,
    videoAspectRatio: Double?,
    screenOrientation: ScreenOrientation,
    rotationOverride: ScreenOrientation?,
): Boolean {
    if (mode != OrientationMode.FollowVideo) return false
    val ratio = videoAspectRatio?.takeIf { it.isFinite() && it >= 1.0 } ?: return false
    if (screenOrientation.isLandscape()) return false
    return playerOrientation(mode, ratio, rotationOverride).isLandscape()
}

/**
 * 跟随视频时是否正在等视频方向：设置为跟随视频、比例未知、当前条目有视频方向，且没有手动转屏。
 *
 * 等待期内锁住当前方向（`SCREEN_ORIENTATION_LOCKED`），视频方向一到达就按它下发一次。
 * 比例为 NaN、非正等无效值时同样算未知。
 */
fun awaitsVideoOrientation(
    mode: OrientationMode,
    videoAspectRatio: Double?,
    audioOnly: Boolean,
    rotationOverride: ScreenOrientation?,
): Boolean {
    if (mode != OrientationMode.FollowVideo) return false
    if (rotationOverride != null) return false
    // 纯音频没有视频方向，等下去会永久占住方向。
    if (audioOnly) return false
    return !hasKnownVideoAspectRatio(videoAspectRatio)
}

/** 转屏按钮：按当前是否为横屏给出另一个方向。 */
fun toggledOrientation(isLandscape: Boolean): ScreenOrientation =
    if (isLandscape) ScreenOrientation.Portrait else ScreenOrientation.Landscape
