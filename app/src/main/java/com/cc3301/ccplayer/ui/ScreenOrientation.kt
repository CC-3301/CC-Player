package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import kotlin.math.abs

/**
 * 播放页要施加的朝向：`System` 在播放页里四向全跟、播放页外交回系统，
 * `Portrait` / `ReversePortrait` 为竖屏族正向与反向，`Landscape` / `ReverseLandscape` 为横屏族正向与反向。
 */
enum class ScreenOrientation { System, Portrait, ReversePortrait, Landscape, ReverseLandscape }

/** 是否属于横屏族。 */
fun ScreenOrientation.isLandscape(): Boolean =
    this == ScreenOrientation.Landscape || this == ScreenOrientation.ReverseLandscape

/**
 * 按方向设置与视频宽高比判定期望的族（ADR-0013）。
 *
 * 跟随视频：宽高比 ≥1 取横屏族，<1 取竖屏族；比例未知、非正或非有限时按屏幕方向处理
 * （四向全跟），避免在拿到比例之前把画面锁死。
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

/** 播放页朝向：本次播放的转屏结果始终优先（包括跟随视频），没有时按设置判定（ADR-0025）。 */
fun playerOrientation(
    mode: OrientationMode,
    videoAspectRatio: Double?,
    rotationOverride: ScreenOrientation?,
): ScreenOrientation = rotationOverride ?: resolvedOrientation(mode, videoAspectRatio)

/** 转屏按钮：按当前是否为横屏给出另一个方向。 */
fun toggledOrientation(isLandscape: Boolean): ScreenOrientation =
    if (isLandscape) ScreenOrientation.Portrait else ScreenOrientation.Landscape

/** 换向迟滞带宽：新方向要比当前方向接近至少这么多度才换，平放或斜放不来回跳。 */
private const val ORIENTATION_HYSTERESIS_DEGREES = 30

/**
 * 四个方向在传感器角度上的位置：竖屏 0°、横屏 270°、反向竖屏 180°、反向横屏 90°。
 *
 * 角度沿用 `OrientationEventListener` 的口径：0° 竖屏正向、90° 左侧朝上（反向横屏）、
 * 180° 反向竖屏、270° 右侧朝上（横屏正向）。顺序同时决定正反向同距时的优先方向。
 */
private val ORIENTATION_ANGLES = listOf(
    ScreenOrientation.Portrait to 0,
    ScreenOrientation.Landscape to 270,
    ScreenOrientation.ReversePortrait to 180,
    ScreenOrientation.ReverseLandscape to 90,
)

/** 期望族允许的方向；[ScreenOrientation.System] 四向全跟。 */
private fun allowedOrientations(requested: ScreenOrientation): List<Pair<ScreenOrientation, Int>> =
    when (requested) {
        ScreenOrientation.System -> ORIENTATION_ANGLES
        ScreenOrientation.Portrait, ScreenOrientation.ReversePortrait ->
            ORIENTATION_ANGLES.filterNot { it.first.isLandscape() }
        ScreenOrientation.Landscape, ScreenOrientation.ReverseLandscape ->
            ORIENTATION_ANGLES.filter { it.first.isLandscape() }
    }

/** 圆周角差，取 0..180。 */
private fun angleDistance(a: Int, b: Int): Int {
    val diff = abs(a - b) % 360
    return if (diff > 180) 360 - diff else diff
}

/**
 * 由传感器角度判定播放页要施加的四向朝向（ADR-0013）。
 *
 * [requested] 是期望族：[ScreenOrientation.System] 四向全跟，横屏与竖屏档只在各自族内判正向与
 * 反向，物理转 90° 不跨族。[angleDegrees] 为空表示还没收到角度或设备平放无法判定，此时保持
 * [current]；[current] 不属于期望族时取该族正向。换向需新方向比当前方向接近至少 30°，即相邻
 * 方向间偏离当前超过 60° 才进入、回到 30° 以内才退回。
 */
fun sensorScreenOrientation(
    requested: ScreenOrientation,
    angleDegrees: Int?,
    current: ScreenOrientation?,
): ScreenOrientation {
    val allowed = allowedOrientations(requested)
    if (angleDegrees == null) {
        return allowed.firstOrNull { it.first == current }?.first ?: allowed.first().first
    }
    val angle = ((angleDegrees % 360) + 360) % 360
    val nearest = allowed.minBy { angleDistance(it.second, angle) }
    if (current == null || allowed.none { it.first == current }) return nearest.first
    if (nearest.first == current) return current
    val currentAngle = allowed.first { it.first == current }.second
    val closer = angleDistance(currentAngle, angle) - angleDistance(nearest.second, angle)
    return if (closer > ORIENTATION_HYSTERESIS_DEGREES) nearest.first else current
}
