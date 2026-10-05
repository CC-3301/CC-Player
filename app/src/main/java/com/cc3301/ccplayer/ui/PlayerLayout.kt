package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** 视频区矩形：坐标与尺寸相对容器左上角，单位像素。 */
data class VideoViewport(val left: Float, val top: Float, val width: Float, val height: Float)

/** 竖屏播放时视频区上方预留给标题/控制位的高度；0 表示不留白。 */
val PLAYER_TOP_RESERVED_HEIGHT: Dp = 0.dp

/**
 * 视频区矩形：按 [videoAspectRatio]（宽/高）等比缩放后居中，不拉伸不裁剪。
 * 容器高度先扣掉 [topReservedHeight]，矩形的居中在剩余区域内进行。
 * 比例未知、非正或非有限时铺满剩余区域；剩余区域非正时铺满容器。
 */
fun videoViewport(
    containerWidth: Float,
    containerHeight: Float,
    videoAspectRatio: Double?,
    topReservedHeight: Float,
): VideoViewport {
    val width = containerWidth.coerceAtLeast(0f)
    val height = containerHeight.coerceAtLeast(0f)
    val reserved = topReservedHeight.coerceIn(0f, height)
    val areaHeight = height - reserved
    val ratio = videoAspectRatio?.takeIf { it.isFinite() && it > 0.0 }
    if (width <= 0f || areaHeight <= 0f) {
        return if (width <= 0f && height <= 0f) VideoViewport(0f, 0f, 0f, 0f)
        else VideoViewport(0f, 0f, width, height)
    }
    if (ratio == null) return VideoViewport(0f, reserved, width, areaHeight)
    val fittedWidth = min(width, (areaHeight * ratio).toFloat())
    val fittedHeight = (fittedWidth / ratio).toFloat()
    return VideoViewport(
        left = (width - fittedWidth) / 2f,
        top = reserved + (areaHeight - fittedHeight) / 2f,
        width = fittedWidth,
        height = fittedHeight,
    )
}
