package com.cc3301.ccplayer.library

/** 抽帧策略版本；策略变化时缓存键随之变化。 */
internal const val THUMBNAIL_POLICY_VERSION = 1

/** 目标宽度（像素）：抽到的帧先缩到该宽度再压 JPEG。 */
internal const val THUMBNAIL_TARGET_WIDTH = 512

/** 抽帧 JPEG 质量。 */
internal const val THUMBNAIL_JPEG_QUALITY = 85

/** 本地视频最多抽取的帧数。 */
internal const val LOCAL_FRAME_COUNT = 3

/** 网络视频最多抽取的帧数。 */
internal const val NETWORK_FRAME_COUNT = 2

/** 全部候选都不合格时退回的比例位置。 */
internal const val FALLBACK_FRAME_FRACTION = 1.0 / 3.0

/** 时长未知时尝试的抽帧位置（微秒）。 */
internal const val UNKNOWN_DURATION_FRAME_MICROS = 1_000_000L

/** 修改时间缺失时缓存键的有效期。 */
internal const val MISSING_MODIFIED_VALIDITY_MILLIS = 24 * 60 * 60 * 1000L

/** 纯色判定：采样亮度的最大值与最小值之差小于该值视为纯色。 */
internal const val SOLID_LUMINANCE_RANGE = 16

/** 一帧的抽取位置；[fallback] 表示全部不合格时用这一帧兜底。 */
internal data class FramePosition(val micros: Long, val fallback: Boolean)

/**
 * 按比例给出抽帧位置：本地最多三帧，网络最多两帧，都包含约三分之一处的兜底帧。
 * 时长未知时只在约一秒处尝试一次。
 */
internal fun framePositions(durationMillis: Long, frameCount: Int): List<FramePosition> {
    if (durationMillis <= 0 || frameCount <= 0) {
        return listOf(FramePosition(UNKNOWN_DURATION_FRAME_MICROS, fallback = true))
    }
    val fractions = when {
        frameCount == 1 -> listOf(FALLBACK_FRAME_FRACTION)
        frameCount == 2 -> listOf(FALLBACK_FRAME_FRACTION, 2.0 / 3.0)
        else -> listOf(0.1, FALLBACK_FRAME_FRACTION, 2.0 / 3.0)
    }
    val durationMicros = durationMillis * 1_000
    return fractions.map { fraction ->
        FramePosition(
            micros = (durationMicros * fraction).toLong().coerceIn(0L, durationMicros - 1),
            fallback = fraction == FALLBACK_FRAME_FRACTION,
        )
    }.distinctBy { it.micros }
}

/** 优先返回第一帧非纯色帧；全部纯色时退回兜底帧。 */
internal fun <T> chooseFrame(frames: List<Pair<FramePosition, T>>, isSolid: (T) -> Boolean): T? =
    frames.firstOrNull { (_, frame) -> !isSolid(frame) }?.second
        ?: frames.firstOrNull { (position, _) -> position.fallback }?.second

/** 采样亮度判纯色：亮度差小于阈值即视为纯色。 */
internal fun isSolidLuminance(luminances: IntArray): Boolean {
    if (luminances.isEmpty()) return true
    return luminances.max() - luminances.min() < SOLID_LUMINANCE_RANGE
}

/** 缩放后的宽度：超过目标宽度就缩到目标宽度，小图保持原样。 */
internal fun targetFrameWidth(originalWidth: Int, targetWidth: Int = THUMBNAIL_TARGET_WIDTH): Int =
    if (originalWidth <= 0) 0 else minOf(originalWidth, targetWidth)
