package com.cc3301.ccplayer.library

import java.io.IOException

/** 抽帧策略版本；策略变化时缓存键随之变化。 */
internal const val THUMBNAIL_POLICY_VERSION = 3

/** 目标宽度（像素）：抽到的帧缩放到该宽度再压 JPEG。 */
internal const val THUMBNAIL_TARGET_WIDTH = 512

/** 网络预览目标宽度：网络只按 320 宽输出，减少解码与图像开销。 */
internal const val NETWORK_TARGET_WIDTH = 320

/** 输出宽度的下限；源图更窄时放大到该宽度。 */
internal const val THUMBNAIL_MIN_WIDTH = 320

/** 输出宽度的上限。 */
internal const val THUMBNAIL_MAX_WIDTH = 512

/** 抽帧 JPEG 质量。 */
internal const val THUMBNAIL_JPEG_QUALITY = 85

/** 本地视频最多抽取的帧数。 */
internal const val LOCAL_FRAME_COUNT = 3

/** 网络单帧的最早位置：1 秒。 */
internal const val NETWORK_MIN_FRAME_POSITION_MICROS = 1_000_000L

/** 全部候选都不合格时退回的比例位置。 */
internal const val FALLBACK_FRAME_FRACTION = 1.0 / 3.0

/** 时长未知时依次尝试的抽帧位置（微秒）；从片头开始，最后一个用于兜底。 */
private val UNKNOWN_DURATION_POSITIONS_MICROS = listOf(0L, 1_000_000L, 3_000_000L, 5_000_000L)

/** 修改时间缺失时缓存键的有效期。 */
internal const val MISSING_MODIFIED_VALIDITY_MILLIS = 24 * 60 * 60 * 1000L

/** 纯色判定：采样亮度的最大值与最小值之差小于该值视为纯色。 */
internal const val SOLID_LUMINANCE_RANGE = 16

/** 一帧的抽取位置；[fallback] 表示全部不合格时用这一帧兜底。 */
internal data class FramePosition(val micros: Long, val fallback: Boolean)

/**
 * 本地按比例给出抽帧位置：最多三帧，包含约三分之一处的兜底帧。
 * 时长未知时也取多个固定位置，最后一个兜底。
 */
internal fun framePositions(durationMillis: Long, frameCount: Int): List<FramePosition> {
    if (frameCount <= 0) return emptyList()
    if (durationMillis <= 0) {
        val count = frameCount.coerceAtMost(UNKNOWN_DURATION_POSITIONS_MICROS.size)
        return UNKNOWN_DURATION_POSITIONS_MICROS.take(count).mapIndexed { index, micros ->
            FramePosition(micros, fallback = index == count - 1)
        }
    }
    val fractions = listOf(0.1, FALLBACK_FRAME_FRACTION, 2.0 / 3.0).take(frameCount)
    val durationMicros = durationMillis * 1_000
    return fractions.map { fraction ->
        FramePosition(
            micros = (durationMicros * fraction).toLong().coerceIn(0L, durationMicros - 1),
            fallback = fraction == FALLBACK_FRAME_FRACTION,
        )
    }.distinctBy { it.micros }
}

/** 网络只取一帧：位置为 max(1% 时长, 1 秒)；时长未知时取 1 秒，不取片头。 */
internal fun networkFramePosition(durationMillis: Long?): FramePosition {
    val durationMicros = durationMillis?.takeIf { it > 0 }?.times(1_000)
    val micros = durationMicros?.let { maxOf(it / 100, NETWORK_MIN_FRAME_POSITION_MICROS) }
        ?: NETWORK_MIN_FRAME_POSITION_MICROS
    return FramePosition(micros, fallback = true)
}

/**
 * 网络单帧抽取：只解码一个位置；解不出或纯色时返回 null，界面保持占位图。
 * [release] 在纯色帧被丢弃前调用，用于回收位图。
 */
internal fun <T> extractNetworkFrame(
    durationMillis: Long?,
    decode: (FramePosition) -> T?,
    isSolid: (T) -> Boolean,
    release: (T) -> Unit = {},
): T? {
    val frame = decode(networkFramePosition(durationMillis)) ?: return null
    if (!isSolid(frame)) return frame
    release(frame)
    return null
}

/** 按位置解码候选帧；解不出的位置直接丢弃。 */
internal fun <T> decodedFrames(
    positions: List<FramePosition>,
    decode: (FramePosition) -> T?,
): List<Pair<FramePosition, T>> =
    positions.mapNotNull { position -> decode(position)?.let { frame -> position to frame } }

/** 优先返回第一帧非纯色帧；全部纯色时退回兜底帧；兜底帧缺失时用任一已解帧。 */
internal fun <T> chooseFrame(frames: List<Pair<FramePosition, T>>, isSolid: (T) -> Boolean): T? =
    frames.firstOrNull { (_, frame) -> !isSolid(frame) }?.second
        ?: frames.firstOrNull { (position, _) -> position.fallback }?.second
        ?: frames.firstOrNull()?.second

/** 候选帧全部解码失败属于抽取失败，不能落成“确定没有图”。 */
internal fun <T> requireDecodedFrame(
    frames: List<Pair<FramePosition, T>>,
    isSolid: (T) -> Boolean,
): T {
    if (frames.isEmpty()) throw IOException("没有解出可用的视频帧")
    return chooseFrame(frames, isSolid) ?: throw IOException("没有解出可用的视频帧")
}

/** 帧压缩失败同样属于抽取失败。 */
internal fun requireCompressedFrame(bytes: ByteArray?): ByteArray =
    bytes ?: throw IOException("视频帧压缩失败")

/** 封面解码的降采样倍数：按 2 的幂降采样，避免先解出全尺寸大图。 */
internal fun coverSampleSize(sourceWidth: Int, targetWidth: Int): Int {
    if (sourceWidth <= 0 || targetWidth <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
    return sample
}

/** 采样亮度判纯色：亮度差小于阈值即视为纯色。 */
internal fun isSolidLuminance(luminances: IntArray): Boolean {
    if (luminances.isEmpty()) return true
    return luminances.max() - luminances.min() < SOLID_LUMINANCE_RANGE
}

/** 策略目标宽度的有效值；低于下限时按 320 输出，缓存键也必须用这个值。 */
internal fun effectiveTargetWidth(targetWidth: Int): Int =
    targetWidth.coerceIn(THUMBNAIL_MIN_WIDTH, THUMBNAIL_MAX_WIDTH)

/** 每个条目实际使用的目标宽度：网络固定 320，本地按策略值；缓存键必须用这个值。 */
internal fun previewTargetWidth(file: MediaFile, policy: ThumbnailPolicy): Int =
    if (file.source == MediaSource.Network) NETWORK_TARGET_WIDTH else effectiveTargetWidth(policy.targetWidth)

/** 缩放后的宽度：输出钳在 320–512，源图更窄时放大到下限。 */
internal fun targetFrameWidth(originalWidth: Int, targetWidth: Int = THUMBNAIL_TARGET_WIDTH): Int {
    if (originalWidth <= 0) return 0
    return originalWidth.coerceIn(THUMBNAIL_MIN_WIDTH, effectiveTargetWidth(targetWidth))
}
