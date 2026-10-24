package com.cc3301.ccplayer.playback

import kotlin.math.roundToInt

/** 从正方形抓帧里取出的中心区域；[x]、[y] 为左上角像素。 */
internal data class FrameCrop(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 冻结画面裁切：抓帧接口只返回中心正方形的图，按视频宽高比取中心区域，
 * 避免冻结画面以正方形铺在屏幕上。视频尺寸未知时保留整张抓帧。
 */
internal fun freezeFrameCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
): FrameCrop {
    if (sourceWidth <= 0 || sourceHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
        return FrameCrop(0, 0, sourceWidth.coerceAtLeast(0), sourceHeight.coerceAtLeast(0))
    }
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val videoAspect = videoWidth.toDouble() / videoHeight
    val width: Int
    val height: Int
    if (videoAspect >= sourceAspect) {
        width = sourceWidth
        height = (sourceWidth / videoAspect).roundToInt().coerceIn(1, sourceHeight)
    } else {
        width = (sourceHeight * videoAspect).roundToInt().coerceIn(1, sourceWidth)
        height = sourceHeight
    }
    return FrameCrop(
        x = (sourceWidth - width) / 2,
        y = (sourceHeight - height) / 2,
        width = width,
        height = height,
    )
}
