package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode

/** 播放页要施加的朝向：[System] 交回系统自动旋转设置，[Landscape] / [Portrait] 固定。 */
enum class ScreenOrientation { System, Landscape, Portrait }

/**
 * 按方向设置与视频宽高比判定播放页朝向（ADR-0013）。
 *
 * 跟随视频：宽高比 ≥1 取横屏，<1 取竖屏；比例未知、非正或非有限时交回系统，
 * 避免在拿到比例之前把画面锁死。
 */
fun resolvedOrientation(mode: OrientationMode, videoAspectRatio: Double?): ScreenOrientation =
    when (mode) {
        OrientationMode.System -> ScreenOrientation.System
        OrientationMode.Landscape -> ScreenOrientation.Landscape
        OrientationMode.Portrait -> ScreenOrientation.Portrait
        OrientationMode.FollowVideo -> when {
            videoAspectRatio == null || !videoAspectRatio.isFinite() || videoAspectRatio <= 0.0 ->
                ScreenOrientation.System
            videoAspectRatio >= 1.0 -> ScreenOrientation.Landscape
            else -> ScreenOrientation.Portrait
        }
    }
