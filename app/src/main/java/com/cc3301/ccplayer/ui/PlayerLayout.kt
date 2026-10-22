package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** 视频区矩形：坐标与尺寸相对容器左上角，单位像素。 */
data class VideoViewport(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * 视频区矩形：按 [videoAspectRatio]（宽/高）等比缩放后居中，不拉伸不裁剪。
 * 比例未知、非正或非有限时铺满容器；容器尺寸非正时给出零尺寸矩形。
 */
fun videoViewport(
    containerWidth: Float,
    containerHeight: Float,
    videoAspectRatio: Double?,
): VideoViewport {
    val width = containerWidth.coerceAtLeast(0f)
    val height = containerHeight.coerceAtLeast(0f)
    val ratio = videoAspectRatio?.takeIf { it.isFinite() && it > 0.0 }
    if (width <= 0f || height <= 0f || ratio == null) {
        return if (width <= 0f || height <= 0f) VideoViewport(0f, 0f, 0f, 0f)
        else VideoViewport(0f, 0f, width, height)
    }
    val fittedWidth = min(width, (height * ratio).toFloat())
    val fittedHeight = (fittedWidth / ratio).toFloat()
    return VideoViewport(
        left = (width - fittedWidth) / 2f,
        top = (height - fittedHeight) / 2f,
        width = fittedWidth,
        height = fittedHeight,
    )
}

/** 播放页次级按钮与上一集/下一集的触控边长；视觉图标小于该尺寸。 */
val PlayerSideTouchTarget = 48.dp

/** 播放页次级按钮的视觉图标尺寸。 */
val PlayerSideIconSize = 26.dp

/** 进度条整行触控高度。 */
val PlayerSeekTouchHeight = 36.dp

/** 进度条时间标签距屏幕左右边缘的距离。 */
val PlayerSeekHorizontalInset = 16.dp

/** 进度条时间标签与轨道之间的间距。 */
val PlayerSeekTimeGap = 16.dp

/** 进度条圆形滑块直径。 */
val PlayerSeekThumbSize = 16.dp

/** 进度条视觉轨道线宽。 */
val PlayerSeekTrackHeight = 2.dp

/** 播放列表条目距屏幕左右边缘的距离。 */
val PlayerPlaylistHorizontalInset = 16.dp

/** 播放列表编号与标题之间的间距。 */
val PlayerPlaylistIndexGap = 16.dp

/** 上一集/下一集按钮与图标尺寸（触控盒 48dp，ADR-0025）。 */
val PlayerTransportButtonSize = 48.dp
val PlayerTransportIconSize = 28.dp

/** 播放/暂停按钮与图标尺寸（ADR-0025）。 */
val PlayerPlayPauseButtonSize = 58.dp
val PlayerPlayPauseNarrowSize = 52.dp
val PlayerPlayPauseIconSize = 34.dp

/** 传输三键间距（ADR-0025）；窄屏压缩到 8dp。 */
val PlayerTransportGap = 16.dp
val PlayerTransportNarrowGap = 8.dp

/** 图标条支持的最小可用宽度；低于该宽度不再保证三组按钮不重叠。 */
val PlayerIconBarMinimumWidth =
    PlayerTransportButtonSize * 2 + PlayerPlayPauseNarrowSize + PlayerTransportNarrowGap * 2 +
        PlayerSideTouchTarget * 2

/**
 * 底部图标条布局：宽屏保持 ADR 尺寸；空间不足时略缩播放键与间距；
 * 仍不足时把字幕与播放列表上下排列，保证三组按钮不重叠。
 *
 * [previousRightStacked] 用于宽度接近阈值时保持原排布，避免在两种布局间抖动。
 * 低于 [PlayerIconBarMinimumWidth] 时布局不再保证不重叠。
 */
data class PlayerIconBarMetrics(
    val availableWidth: Dp,
    val sideButtonSize: Dp,
    val previousNextSize: Dp,
    val playPauseSize: Dp,
    val gap: Dp,
    val rightStacked: Boolean,
) {
    val transportWidth: Dp = previousNextSize * 2 + playPauseSize + gap * 2
    val transportStart: Dp = (availableWidth - transportWidth) / 2f
    val transportEnd: Dp = transportStart + transportWidth
    val leftGroupEnd: Dp = sideButtonSize
    val rightGroupWidth: Dp = if (rightStacked) sideButtonSize else sideButtonSize * 2
    val rightGroupStart: Dp = availableWidth - rightGroupWidth
    val nextStart: Dp = transportEnd - previousNextSize
    val nextIconStart: Dp = nextStart + (previousNextSize - PlayerTransportIconSize) / 2f
    val nextIconEnd: Dp = nextIconStart + PlayerTransportIconSize

    /** 图标条高度：普通排布取三键最大尺寸，上下排列时容纳两个侧键。 */
    val barHeight: Dp = if (rightStacked) {
        maxOf(previousNextSize, playPauseSize, sideButtonSize * 2)
    } else {
        maxOf(previousNextSize, playPauseSize)
    }

    /** 左右两组与中间三键是否重叠。 */
    val overlapped: Boolean =
        leftGroupEnd > transportStart || transportEnd > rightGroupStart
}

/** 松手后预览保留到播放位置追平的时间容差，毫秒。 */
const val SEEK_PREVIEW_SETTLE_MILLIS = 1_000L

/** 预览保留的最长时间，避免 seek 未回传时时间停在旧值，毫秒。 */
const val SEEK_PREVIEW_HOLD_MILLIS = 5_000L

/** 进度条视觉轨道的像素范围。 */
data class SeekBarTrackBounds(val start: Float, val end: Float)

/**
 * 拖动期间两侧时间标签共用的冻结宽度：取两者较大值。
 * 拖动中的预览时间不超过总时长，因此不会因跨位数被裁切，轨道也不会随标签变宽而移动。
 */
fun seekBarDragTimeWidth(leftTimeWidthPx: Float, rightTimeWidthPx: Float): Float =
    maxOf(leftTimeWidthPx, rightTimeWidthPx)

/**
 * 计算进度条视觉轨道范围：两侧时间区、间距与滑块半径都从整行宽度中扣除。
 * 空间不足时两端退到同一位置，交由 [seekBarMillisAt] 判为不可拖动。
 */
fun seekBarTrackBounds(
    widthPx: Float,
    leftTimeWidthPx: Float,
    rightTimeWidthPx: Float,
    spacingPx: Float,
    thumbRadiusPx: Float,
): SeekBarTrackBounds {
    val half = widthPx / 2f
    val start = (leftTimeWidthPx + spacingPx + thumbRadiusPx).coerceIn(0f, half)
    val end = (widthPx - rightTimeWidthPx - spacingPx - thumbRadiusPx).coerceIn(start, widthPx)
    return SeekBarTrackBounds(start, end)
}

/**
 * 把进度条整行的横向坐标换算成毫秒。
 * [trackStart] 与 [trackEnd] 为视觉轨道的像素范围；落在两侧时间区时取最近端点。
 * 轨道无效或时长未知时返回 null。
 */
fun seekBarMillisAt(
    x: Float,
    trackStart: Float,
    trackEnd: Float,
    durationMillis: Long,
): Long? {
    if (trackEnd <= trackStart || durationMillis <= 0L) return null
    val fraction = ((x - trackStart) / (trackEnd - trackStart)).coerceIn(0f, 1f)
    return (fraction * durationMillis).roundToLong().coerceIn(0L, durationMillis)
}

/**
 * 按下首帧的目标时间：按冻结前的左右标签宽度映射。
 * 左右标签宽度不等时，冻结会移动轨道端点；首帧必须先按用户实际按下的旧轨道计算，避免跳位。
 */
fun seekBarDownMillis(
    x: Float,
    widthPx: Float,
    leftTimeWidthPx: Float,
    rightTimeWidthPx: Float,
    spacingPx: Float,
    thumbRadiusPx: Float,
    durationMillis: Long,
): Long? {
    val bounds = seekBarTrackBounds(
        widthPx = widthPx,
        leftTimeWidthPx = leftTimeWidthPx,
        rightTimeWidthPx = rightTimeWidthPx,
        spacingPx = spacingPx,
        thumbRadiusPx = thumbRadiusPx,
    )
    return seekBarMillisAt(x, bounds.start, bounds.end, durationMillis)
}

/**
 * 末次拖动预览是否继续保留：未切媒体、未超时，且播放位置尚未到达目标。
 * 按 [baseMillis]（拖动起点）判断方向；位置越过目标时立即停止保留。
 */
fun shouldKeepSeekPreview(
    positionMillis: Long,
    heldMillis: Long?,
    baseMillis: Long?,
    mediaChanged: Boolean,
    timedOut: Boolean,
): Boolean {
    if (heldMillis == null || mediaChanged || timedOut) return false
    if (abs(positionMillis - heldMillis) <= SEEK_PREVIEW_SETTLE_MILLIS) return false
    val base = baseMillis ?: return true
    return if (heldMillis >= base) {
        positionMillis < heldMillis - SEEK_PREVIEW_SETTLE_MILLIS
    } else {
        positionMillis > heldMillis + SEEK_PREVIEW_SETTLE_MILLIS
    }
}

/** 拖动事件里是否出现主指针以外的按下指针；出现时取消本次手势。 */
fun isMultiPointerDrag(primaryId: Long, pressedPointerIds: List<Long>): Boolean =
    pressedPointerIds.any { it != primaryId }

/** 按图标条可用宽度计算三组按钮的尺寸与排布。 */
fun playerIconBarMetrics(
    availableWidth: Dp,
    previousRightStacked: Boolean = false,
): PlayerIconBarMetrics {
    val wideTransport = PlayerTransportButtonSize * 2 + PlayerPlayPauseButtonSize + PlayerTransportGap * 2
    val narrowTransport = PlayerTransportButtonSize * 2 + PlayerPlayPauseNarrowSize + PlayerTransportNarrowGap * 2
    val horizontalSideWidth = PlayerSideTouchTarget * 2
    val wideThreshold = wideTransport + horizontalSideWidth * 2
    val narrowHorizontalThreshold = narrowTransport + horizontalSideWidth * 2

    fun metrics(playPause: Dp, gap: Dp, stacked: Boolean) = PlayerIconBarMetrics(
        availableWidth = availableWidth,
        sideButtonSize = PlayerSideTouchTarget,
        previousNextSize = PlayerTransportButtonSize,
        playPauseSize = playPause,
        gap = gap,
        rightStacked = stacked,
    )

    return when {
        availableWidth >= wideThreshold -> metrics(
            PlayerPlayPauseButtonSize,
            PlayerTransportGap,
            stacked = false,
        )

        // 已处于上下排列时保持到宽屏阈值，避免在窄屏横排边界来回切换。
        availableWidth >= narrowHorizontalThreshold && !previousRightStacked -> metrics(
            PlayerPlayPauseNarrowSize,
            PlayerTransportNarrowGap,
            stacked = false,
        )

        else -> metrics(
            PlayerPlayPauseNarrowSize,
            PlayerTransportNarrowGap,
            stacked = true,
        )
    }
}
