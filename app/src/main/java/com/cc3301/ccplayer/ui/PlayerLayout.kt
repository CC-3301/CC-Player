package com.cc3301.ccplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.ccplayer.ui.theme.AppColors
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

/**
 * 播放页黑底层规则：只有片尾、首次缓冲、或切集时已明确没有冻结画面才铺黑底。
 * 抓帧还在途时原画面仍留在 Surface 上，不铺黑底。
 */
fun showPlayerBlackLayer(
    holdBlack: Boolean,
    buffering: Boolean,
    switching: Boolean,
    hasFreezeFrame: Boolean,
    freezeFramePending: Boolean,
): Boolean = holdBlack || (buffering && !switching) ||
    (switching && !hasFreezeFrame && !freezeFramePending)

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

/** 播放列表条目内容距抽屉左右边缘的距离。 */
val PlayerPlaylistHorizontalInset = 16.dp

/** 播放列表当前项高亮条左边缘距抽屉左边缘的距离；0dp 表示贴边。 */
val PlayerPlaylistHighlightStartInset = 0.dp

/** 播放列表当前项高亮条右边缘距抽屉右边缘的距离。 */
val PlayerPlaylistHighlightEndInset = 4.dp

/** 播放列表编号与标题之间的间距。 */
val PlayerPlaylistIndexGap = 16.dp

/** 播放列表编号颜色。 */
val PlayerPlaylistIndexColor = Color.White

/** 播放列表当前项三角图标字号。 */
val PlayerPlaylistArrowFontSize = 17.25.sp

/** 播放列表当前项三角图标宽度。 */
val PlayerPlaylistArrowWidth = 24.dp

/** 播放列表当前项三角图标颜色。 */
val PlayerPlaylistArrowColor = AppColors.Accent

/** 播放列表当前项高亮的横向像素范围。 */
data class PlaylistHighlightBounds(val start: Float, val end: Float)

/** 按行宽与左右内缩像素计算高亮范围；行宽不足时两端重合。 */
fun playlistHighlightBounds(
    rowWidthPx: Float,
    startInsetPx: Float,
    endInsetPx: Float,
): PlaylistHighlightBounds {
    val width = rowWidthPx.coerceAtLeast(0f)
    val start = startInsetPx.coerceIn(0f, width / 2f)
    val end = (width - endInsetPx).coerceAtLeast(start)
    return PlaylistHighlightBounds(start, end)
}

/** 播放页可按压的区域，用于确定按压反馈的覆盖范围。 */
enum class PlayerPressTarget {
    IconButton,
    MorePanelRow,
    AutoPlayNextRow,
    TextButton,
    PlaylistRow,
    SubtitleRow,
    LockLayer,
    OutsideDismissLayer,
}

/** 按压反馈只覆盖交互控件；锁屏整屏层与面板外关闭层不显示涟漪。 */
fun playerPressFeedbackEnabled(target: PlayerPressTarget): Boolean = when (target) {
    PlayerPressTarget.IconButton,
    PlayerPressTarget.MorePanelRow,
    PlayerPressTarget.AutoPlayNextRow,
    PlayerPressTarget.TextButton,
    PlayerPressTarget.PlaylistRow,
    PlayerPressTarget.SubtitleRow,
    -> true

    PlayerPressTarget.LockLayer,
    PlayerPressTarget.OutsideDismissLayer,
    -> false
}

/** 播放页按压涟漪颜色。 */
val PlayerPressRippleColor = Color.White

/** 播放页按压涟漪不透明度。 */
const val PLAYER_PRESS_RIPPLE_ALPHA = 0.2f

/** 缓冲文字描边宽度。 */
val PlayerBufferingTextStrokeWidth = 2.dp

/** 缓冲文字描边（深色 scrim）不透明度。 */
const val PLAYER_BUFFERING_TEXT_SCRIM_ALPHA = 0.85f

/** 缓冲文字描边颜色。 */
val PlayerBufferingTextScrimColor = Color(0xFF020509)

/** 缓冲转圈尺寸。 */
val PlayerBufferingSpinnerSize = 36.dp

/** 缓冲转圈粗细。 */
val PlayerBufferingSpinnerStrokeWidth = 3.dp

/** 缓冲转圈颜色。 */
val PlayerBufferingSpinnerColor = AppColors.Accent

/** 上一集/播放/下一集热区：各 62 × 58dp、总宽 186dp、彼此无间隙（ADR-0025）。 */
val PlayerTransportButtonWidth = 62.dp
val PlayerTransportButtonHeight = 58.dp
val PlayerTransportTotalWidth = PlayerTransportButtonWidth * 3

/** 侧键图标 28dp，播放键图标 34dp（ADR-0025）。 */
val PlayerTransportIconSize = 28.dp
val PlayerPlayPauseIconSize = 34.dp

/**
 * 三键横排阈值：三键居中后两侧余量各为 (可用宽度 - 186) / 2，
 * 右侧要容纳两个 48dp 侧键，因此至少 378dp；不足时右侧按钮上下排列。
 */
val PlayerIconBarHorizontalThreshold = PlayerTransportTotalWidth + PlayerSideTouchTarget * 4

/**
 * 图标条最小可用宽度 282dp：左侧锁定键 + 186dp 三键 + 右侧上下排列的一个侧键。
 * 更窄时锁定键会压到三键，当前布局不压缩三键，因此不支持。
 */
val PlayerIconBarMinimumWidth = PlayerTransportTotalWidth + PlayerSideTouchTarget * 2

/**
 * 底部图标条布局：三键固定 62 × 58dp 居中；空间不足时把字幕与播放列表上下排列，
 * 保证两侧按钮不压到三键。
 *
 * 360dp 等常见宽度下，三键居中后两侧余量不足以容纳右下两个按钮，右侧上下排列是固定热区的几何结果。
 * 低于 [PlayerIconBarMinimumWidth] 时布局不再保证不重叠。
 */
data class PlayerIconBarMetrics(
    val availableWidth: Dp,
    val rightStacked: Boolean,
) {
    val transportButtonWidth: Dp = PlayerTransportButtonWidth
    val transportButtonHeight: Dp = PlayerTransportButtonHeight
    val transportTotalWidth: Dp = PlayerTransportTotalWidth

    /** 图标条高度：普通排布取三键高度，上下排列时容纳两个侧键。 */
    val barHeight: Dp = if (rightStacked) {
        maxOf(transportButtonHeight, PlayerSideTouchTarget * 2)
    } else {
        maxOf(transportButtonHeight, PlayerSideTouchTarget)
    }
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

/**
 * 按图标条可用宽度决定右侧字幕与播放列表是否上下排列。
 * 三键横排需要两侧各 96dp 侧键区；不足时右侧改为上下排列，避免压到下一集。
 */
fun playerIconBarMetrics(availableWidth: Dp): PlayerIconBarMetrics =
    PlayerIconBarMetrics(
        availableWidth = availableWidth,
        rightStacked = availableWidth < PlayerIconBarHorizontalThreshold,
    )
