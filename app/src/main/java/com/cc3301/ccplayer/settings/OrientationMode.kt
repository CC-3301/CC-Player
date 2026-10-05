package com.cc3301.ccplayer.settings

/**
 * 播放页方向设置四选一：
 * [System] 屏幕方向（默认，随系统自动旋转设置）、[Landscape] 固定横屏、[Portrait] 固定竖屏、
 * [FollowVideo] 按视频宽高比判定。
 */
enum class OrientationMode { System, Landscape, Portrait, FollowVideo }

/** 控制条按钮的轮换顺序：屏幕方向 → 横屏 → 竖屏 → 跟随视频。 */
fun OrientationMode.next(): OrientationMode = when (this) {
    OrientationMode.System -> OrientationMode.Landscape
    OrientationMode.Landscape -> OrientationMode.Portrait
    OrientationMode.Portrait -> OrientationMode.FollowVideo
    OrientationMode.FollowVideo -> OrientationMode.System
}
