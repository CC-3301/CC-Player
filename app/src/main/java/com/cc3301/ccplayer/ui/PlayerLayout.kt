package com.cc3301.ccplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.ccplayer.ui.theme.AppColors
import kotlin.math.roundToLong

/** 视频区矩形：坐标与尺寸相对容器左上角，单位像素。 */
data class VideoViewport(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * 比例是否能用于方向判断与「比例未知期间铺黑」：非空、有限且大于 0。
 */
fun hasKnownVideoAspectRatio(videoAspectRatio: Double?): Boolean =
    videoAspectRatio != null && videoAspectRatio.isFinite() && videoAspectRatio > 0.0

/**
 * 视频区矩形：始终铺满容器，视频比例与黑边由 mpv 在 Surface 内处理。
 * 容器尺寸非正时给出零尺寸矩形。
 */
fun videoViewport(
    containerWidth: Float,
    containerHeight: Float,
): VideoViewport {
    val width = containerWidth.coerceAtLeast(0f)
    val height = containerHeight.coerceAtLeast(0f)
    return if (width <= 0f || height <= 0f) {
        VideoViewport(0f, 0f, 0f, 0f)
    } else {
        VideoViewport(0f, 0f, width, height)
    }
}

/**
 * 播放页黑底层规则：屏幕上没有可显示的画面、本次播放还没拿到过视频比例，或首次撤黑还在等屏幕方向，就铺黑底。
 * 视频区始终铺满容器；比例未知期间黑底盖住首帧，比例到达即撤掉黑底（横向视频还要等方向到位）。
 *
 * [orientationSettled] 表示本次播放的方向等待已有结论：有结论后就一直为真，之后不再按比例盖。
 * [orientationPending] 表示首次撤黑还在等屏幕转向视频方向（见 [PlayerPlacementGate]）：为真时黑底继续
 * 盖住竖向摆出来的那一帧，方向到位或等待到时限后撤掉。
 * 有没有画面只由控制层派生的 SurfacePresentation 给出，界面不再自行组合加载阶段、黑底与视频轨。
 */
fun showPlayerBlackLayer(
    showsPicture: Boolean,
    videoAspectRatio: Double?,
    orientationSettled: Boolean,
    orientationPending: Boolean,
): Boolean = !showsPicture ||
    orientationPending ||
    (!orientationSettled && !hasKnownVideoAspectRatio(videoAspectRatio))

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

/** 播放列表编号与标题的文字颜色。 */
val PlayerPlaylistTextColor = Color.White

/** 播放列表抽屉底色：统一 50% 黑，不做渐变。 */
val PlayerPlaylistBackgroundColor = Color(0xFF020509).copy(alpha = 0.50f)

/** 播放列表当前项底色：深蓝 30%。 */
val PlayerPlaylistCurrentItemColor = AppColors.AccentDeep.copy(alpha = 0.30f)

/** 播放列表抽屉的落位：横屏占右半屏，竖屏占下半屏。 */
enum class PlaylistDrawerPlacement { RightHalf, BottomHalf }

/** 按窗口宽高决定抽屉落位；宽度大于高度视为横屏，其余按竖屏。 */
fun playlistDrawerPlacement(containerWidth: Float, containerHeight: Float): PlaylistDrawerPlacement =
    if (containerWidth > containerHeight) {
        PlaylistDrawerPlacement.RightHalf
    } else {
        PlaylistDrawerPlacement.BottomHalf
    }

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

/** 缓冲文字可见描边宽度；描边以字形中心为轴，按两倍宽度绘制，露在字形外侧的即此宽度。 */
val PlayerBufferingTextStrokeWidth = 1.dp

/** 缓冲文字描边（深色 scrim）不透明度。 */
const val PLAYER_BUFFERING_TEXT_SCRIM_ALPHA = 0.60f

/** 缓冲文字描边颜色。 */
val PlayerBufferingTextScrimColor = Color(0xFF020509)

/** 缓冲转圈尺寸。 */
val PlayerBufferingSpinnerSize = 36.dp

/** 缓冲转圈粗细。 */
val PlayerBufferingSpinnerStrokeWidth = 3.dp

/** 缓冲转圈颜色。 */
val PlayerBufferingSpinnerColor = AppColors.Accent

/** 上一集/播放/下一集热区：可用宽度足够时各 62 × 58dp、总宽 186dp、彼此无间隙（ADR-0025）。 */
val PlayerTransportButtonWidth = 62.dp

/** 可用宽度不足 [PlayerTransportNarrowWidthThreshold] 时三键热区收窄到的宽度；不得小于 52 × 58dp（ADR-0025）。 */
val PlayerTransportNarrowButtonWidth = 52.dp

val PlayerTransportButtonHeight = 58.dp
val PlayerTransportTotalWidth = PlayerTransportButtonWidth * 3

/** 侧键图标 28dp，播放键图标 34dp（ADR-0025）。 */
val PlayerTransportIconSize = 28.dp
val PlayerPlayPauseIconSize = 34.dp

/**
 * 三键热区收窄阈值 378dp：62dp 三键居中后两侧余量各为 (可用宽度 - 186) / 2，
 * 右侧要容纳两个 48dp 侧键，因此至少 378dp；不足时三键各收窄到 52dp。
 */
val PlayerTransportNarrowWidthThreshold = PlayerTransportTotalWidth + PlayerSideTouchTarget * 4

/**
 * 底部图标条布局：三键组始终居中，两侧通道各为 (可用宽度 - 三键总宽) / 2，
 * 左通道放锁定键，右通道并排放字幕与播放列表。
 *
 * 通道放不下右侧两键时，按通道可用宽度与所需宽度的比值等比缩小全部底部按钮，
 * 热区与图标使用同一系数，六个按钮因此保持同一条水平线、互不重叠，播放键仍严格居中。
 * 可用宽度不足 [PlayerTransportNarrowWidthThreshold] 时三键基准宽度先收窄到 52dp。
 */
data class PlayerIconBarMetrics(
    val availableWidth: Dp,
) {
    /** 三键基准热区宽度：可用宽度不足 [PlayerTransportNarrowWidthThreshold] 时收窄到 52dp。 */
    val transportBaseWidth: Dp = if (availableWidth < PlayerTransportNarrowWidthThreshold) {
        PlayerTransportNarrowButtonWidth
    } else {
        PlayerTransportButtonWidth
    }

    /** 两侧通道所需宽度：右侧字幕与播放列表并排的两个侧键。 */
    val sideChannelRequiredWidth: Dp = PlayerSideTouchTarget * 2

    /** 两侧通道可用宽度。 */
    val sideChannelWidth: Dp = (availableWidth - transportBaseWidth * 3) / 2

    /** 等比缩放系数：通道放得下时不缩放，放不下时按通道比值缩小。 */
    val scale: Float = (sideChannelWidth / sideChannelRequiredWidth).coerceIn(0f, 1f)

    val transportButtonWidth: Dp = transportBaseWidth * scale
    val transportButtonHeight: Dp = PlayerTransportButtonHeight * scale
    val transportTotalWidth: Dp = transportButtonWidth * 3

    /** 缩放后的侧键热区边长：锁定、字幕、播放列表共用。 */
    val sideTouchTarget: Dp = PlayerSideTouchTarget * scale

    /** 缩放后的图标尺寸。 */
    val sideIconSize: Dp = PlayerSideIconSize * scale
    val transportIconSize: Dp = PlayerTransportIconSize * scale
    val playPauseIconSize: Dp = PlayerPlayPauseIconSize * scale
}

/** 拖动提示里目标时间的字号；要明显大于增减量。 */
val PlayerSeekHintTargetFontSize = 48.sp

/** 拖动提示里增减量的字号。 */
val PlayerSeekHintDeltaFontSize = 32.sp

/** 拖动提示两行之间的间距。 */
val PlayerSeekHintLineGap = 6.dp

/** 播放页内鼠标静止多久后隐藏指针。 */
const val PLAYER_POINTER_HIDE_DELAY_MILLIS = 3_000L

/** 指针位移死区：从最后一次有效移动的位置起算，位移不超过它的抖动不算移动。 */
val PlayerPointerMoveDeadZone = 5.dp

/** 拖动跳转的像素死区：从最后一次下发 seek 的手指位置起算，越过它才再下发一次；预览目标不受它影响。 */
val PlayerSeekDragDeadZone = 3.dp

/** 预览保留的最长时间，避免 seek 未回传时时间停在旧值，毫秒。 */
const val SEEK_PREVIEW_HOLD_MILLIS = 5_000L

/**
 * 落定通知是否该撤掉界面的进度保持值：只有比已处理过的通知更新的 id 才撤。
 * 同一目标连续两次落定也能撤；旧代际的迟到通知不会误清新保持值。
 */
fun seekSettlementReleasesHold(heldSettlementId: Long, settlementId: Long): Boolean =
    settlementId > heldSettlementId

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

/** 拖动事件里是否出现主指针以外的按下指针；出现时取消本次手势。 */
fun isMultiPointerDrag(primaryId: Long, pressedPointerIds: List<Long>): Boolean =
    pressedPointerIds.any { it != primaryId }

/**
 * 按图标条可用宽度算出三键热区、两侧热区与图标尺寸。
 * 三键 62dp 居中需两侧各 96dp 侧键区（378dp），不足时三键收窄到 52dp；再不足则全部按钮等比缩小。
 */
fun playerIconBarMetrics(availableWidth: Dp): PlayerIconBarMetrics =
    PlayerIconBarMetrics(availableWidth = availableWidth)
