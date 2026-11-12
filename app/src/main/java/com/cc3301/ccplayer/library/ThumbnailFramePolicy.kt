package com.cc3301.ccplayer.library

import java.io.IOException

/** 抽帧策略版本；策略变化时缓存键随之变化。 */
internal const val THUMBNAIL_POLICY_VERSION = 5

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
internal const val LOCAL_FRAME_COUNT = 2

/** 网络单帧的最早位置：1 秒。 */
internal const val NETWORK_MIN_FRAME_POSITION_MICROS = 1_000_000L

/** 网络单帧的最晚位置：30 秒；再往后要跨很远做网络寻址，单帧会拖到十秒级。 */
internal const val NETWORK_MAX_FRAME_POSITION_MICROS = 30_000_000L

/** 全部候选都不合格时退回的比例位置。 */
internal const val FALLBACK_FRAME_FRACTION = 1.0 / 3.0

/** 时长未知时依次尝试的抽帧位置（微秒）；从片头开始，最后一个用于兜底。 */
private val UNKNOWN_DURATION_POSITIONS_MICROS = listOf(0L, 1_000_000L, 3_000_000L, 5_000_000L)

/** 纯色判定：采样亮度的最大值与最小值之差小于该值视为纯色。 */
internal const val SOLID_LUMINANCE_RANGE = 16

/** 近黑判定：采样亮度的平均值低于该值视为近黑帧，不算可用封面。 */
internal const val DARK_LUMINANCE = 24

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
    val fractions = when (frameCount) {
        1 -> listOf(FALLBACK_FRAME_FRACTION)
        2 -> listOf(FALLBACK_FRAME_FRACTION, 2.0 / 3.0)
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

/** 主候选不合格时退到的位置（微秒）：60 秒。 */
private val NETWORK_FALLBACK_FRAME_POSITION_MICROS = listOf(60_000_000L)

/**
 * 网络主候选位置（微秒）：`min(max(1% 时长, 1 秒), 30 秒)`；时长未知时取 1 秒，不取片头。
 */
internal fun networkFramePosition(durationMillis: Long?): Long {
    val durationMicros = durationMillis?.takeIf { it > 0 }?.times(1_000)
    return durationMicros
        ?.let { (it / 100).coerceIn(NETWORK_MIN_FRAME_POSITION_MICROS, NETWORK_MAX_FRAME_POSITION_MICROS) }
        ?: NETWORK_MIN_FRAME_POSITION_MICROS
}

/** 网络抽帧候选位置（微秒）：主候选之后退到 60 秒，最多两个。 */
internal fun networkFrameCandidates(durationMillis: Long?): List<Long> =
    listOf(networkFramePosition(durationMillis)) + NETWORK_FALLBACK_FRAME_POSITION_MICROS

/**
 * 网络抽帧：按候选位置依次解码，取第一个可用帧。
 * 只有黑帧或纯色帧才退到下一个候选：候选位置是给「画面不可用」准备的，
 * 某个位置解不出图说明这一帧抽不出来，换位置不改变结果，直接按抽取失败收尾（返回 null）。
 * 候选都解码出来仍不合格时直接交出最后解出的那一帧，不再追加候选：
 * 宁可偶尔偏暗，也不为躲黑帧多一次网络寻址。不合格帧由 [release] 回收。
 */
internal suspend fun <T> extractNetworkFrame(
    positions: List<Long>,
    decode: suspend (micros: Long) -> T?,
    isSolid: (T) -> Boolean,
    release: (T) -> Unit = {},
    isDark: (T) -> Boolean = { false },
): T? {
    var lastDecoded: T? = null
    for (micros in positions) {
        val frame = decode(micros) ?: return null
        if (!isSolid(frame) && !isDark(frame)) {
            lastDecoded?.let(release)
            return frame
        }
        lastDecoded?.let(release)
        lastDecoded = frame
    }
    return lastDecoded
}

/** 按位置解码候选帧；解不出的位置直接丢弃。 */
internal fun <T> decodedFrames(
    positions: List<FramePosition>,
    decode: (FramePosition) -> T?,
): List<Pair<FramePosition, T>> =
    positions.mapNotNull { position -> decode(position)?.let { frame -> position to frame } }

/**
 * 优先返回第一帧既非纯色也非近黑的帧；全部不合格时退回兜底帧，但近黑帧始终不算可用封面。
 * 兜底帧缺失或本身近黑时，退回任一非近黑帧。
 */
internal fun <T> chooseFrame(
    frames: List<Pair<FramePosition, T>>,
    isSolid: (T) -> Boolean,
    isDark: (T) -> Boolean = { false },
): T? =
    frames.firstOrNull { (_, frame) -> !isSolid(frame) && !isDark(frame) }?.second
        ?: frames.firstOrNull { (position, frame) -> position.fallback && !isDark(frame) }?.second
        ?: frames.firstOrNull { (_, frame) -> !isDark(frame) }?.second

/** 候选帧全部解码失败属于抽取失败，不能落成“确定没有图”。 */
internal fun <T> requireDecodedFrame(
    frames: List<Pair<FramePosition, T>>,
    isSolid: (T) -> Boolean,
    isDark: (T) -> Boolean = { false },
): T {
    if (frames.isEmpty()) throw IOException("没有解出可用的视频帧")
    return chooseFrame(frames, isSolid, isDark) ?: throw IOException("没有解出可用的视频帧")
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

/** 采样亮度判近黑：平均值低于阈值即视为近黑帧。 */
internal fun isDarkLuminance(luminances: IntArray): Boolean {
    if (luminances.isEmpty()) return true
    return luminances.average() < DARK_LUMINANCE
}

/** 封面可用性：纯色与近黑帧都不算可用封面。 */
internal fun isUnusableLuminance(luminances: IntArray): Boolean =
    isSolidLuminance(luminances) || isDarkLuminance(luminances)

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
