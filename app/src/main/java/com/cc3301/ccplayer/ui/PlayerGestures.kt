package com.cc3301.ccplayer.ui

import kotlin.math.abs
import kotlin.math.roundToLong

/** 双击快退/快进的步长，毫秒。 */
const val SEEK_STEP_MILLIS = 10_000L

/** 中间区域整屏宽度对应的跳转时长，毫秒。 */
const val SEEK_FULL_WIDTH_MILLIS = 300_000L

/** 双击判定窗口，毫秒。 */
const val DOUBLE_TAP_WINDOW_MILLIS = 300L

/** 进入音量调整所需的拖动位移，dp。 */
const val VOLUME_DRAG_THRESHOLD_DP = 9f

/** 左右两侧音量区各占屏宽的比例。 */
const val VOLUME_ZONE_FRACTION = 0.18f

/** 播放页上可触摸的元素。 */
enum class PlayerInput {
    /** 点击屏幕显示/隐藏控制条。 */
    Tap,

    /** 分区双击。 */
    DoubleTap,

    /** 两侧竖条上下拖动调音量。 */
    VolumeDrag,

    /** 中间区域左右拖动跳转。 */
    SeekDrag,

    /** 控制条，含其上进度条的拖动。 */
    ControlBar,

    /** 解锁按钮。 */
    UnlockButton,
}

/** 手势分区：左右各 [VOLUME_ZONE_FRACTION] 竖条为音量区，中间为跳转与双击区。 */
enum class GestureZone {
    LeftVolume,
    Center,
    RightVolume,
}

/** 双击落点所在侧。 */
enum class DoubleTapSide { Left, Right }

/** 双击的结果：相对当前位置跳转，或切换播放暂停。 */
sealed interface DoubleTapAction {
    /** 相对当前位置跳转 [deltaMillis] 毫秒，负数为快退。 */
    data class Seek(val deltaMillis: Long, val side: DoubleTapSide) : DoubleTapAction

    data object TogglePlayPause : DoubleTapAction
}

/** 拖动进入的手势：音量或跳转。 */
sealed interface DragGesture {
    data object Volume : DragGesture

    data object Seek : DragGesture
}

/**
 * 触点所在分区：左边缘到 18%、右边缘向内 18% 为音量区，其余为中间区。
 * 左 18% 分界线归中间区，右 18% 分界线归右侧音量区。
 */
fun gestureZone(x: Float, width: Int): GestureZone {
    if (width <= 0) return GestureZone.Center
    val edge = width * VOLUME_ZONE_FRACTION
    return when {
        x < edge -> GestureZone.LeftVolume
        x >= width - edge -> GestureZone.RightVolume
        else -> GestureZone.Center
    }
}

/** 双击落点对应的动作：左侧倒退 10 秒，中间暂停/继续，右侧前进 10 秒。 */
fun doubleTapAction(x: Float, width: Int): DoubleTapAction = when (gestureZone(x, width)) {
    GestureZone.LeftVolume -> DoubleTapAction.Seek(-SEEK_STEP_MILLIS, DoubleTapSide.Left)
    GestureZone.Center -> DoubleTapAction.TogglePlayPause
    GestureZone.RightVolume -> DoubleTapAction.Seek(SEEK_STEP_MILLIS, DoubleTapSide.Right)
}

/** 相对当前位置跳转的结果：目标位置与实际位移（已夹取到 0..时长）。 */
data class RelativeSeekTarget(val targetMillis: Long, val deltaMillis: Long)

/** 时长未知时不跳转；目标夹取后实际位移可能小于请求步长，甚至为 0。 */
fun relativeSeekTarget(
    positionMillis: Long,
    durationMillis: Long,
    deltaMillis: Long,
): RelativeSeekTarget? {
    if (durationMillis <= 0L) return null
    val target = (positionMillis + deltaMillis).coerceIn(0L, durationMillis)
    return RelativeSeekTarget(target, target - positionMillis)
}

/** 中间区域横向拖动换算的跳转量：整屏宽对应 [SEEK_FULL_WIDTH_MILLIS]，右拖为正。 */
fun seekDeltaMillis(dragX: Float, width: Int): Long {
    if (width <= 0) return 0L
    return (dragX / width * SEEK_FULL_WIDTH_MILLIS).roundToLong()
}

/**
 * 累计位移达到阈值后进入的手势：两侧只接受上下拖动，中间只接受左右拖动。
 * 位移未超过 [thresholdPx] 时返回 null，避免把点按抖动当成拖动。
 */
fun dragGesture(
    zone: GestureZone,
    totalDx: Float,
    totalDy: Float,
    thresholdPx: Float,
): DragGesture? {
    val horizontal = abs(totalDx)
    val vertical = abs(totalDy)
    return when {
        zone != GestureZone.Center && vertical > thresholdPx && vertical >= horizontal ->
            DragGesture.Volume

        zone == GestureZone.Center && horizontal > thresholdPx && horizontal >= vertical ->
            DragGesture.Seek

        else -> null
    }
}

/** 锁定或任一面板打开时，手势层不响应。 */
fun gesturesAllowed(locked: Boolean, panelOpen: Boolean): Boolean = !locked && !panelOpen

/** 切集、首次加载、恢复、面板打开或锁定时，手势反馈应清空且不再显示。 */
fun doubleTapFeedbackBlocked(
    locked: Boolean,
    panelOpen: Boolean,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
): Boolean = locked || panelOpen || switching || initialLoading || restoring

/** 双击跳转反馈是否应触发：手势可用且当前允许跳转。 */
fun doubleTapFeedbackAllowed(
    locked: Boolean,
    panelOpen: Boolean,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
): Boolean = !doubleTapFeedbackBlocked(locked, panelOpen, switching, initialLoading, restoring)

/** 锁定时只放行 [PlayerInput.UnlockButton]；其余元素既不响应手势也不显示。 */
fun inputAllowed(input: PlayerInput, locked: Boolean): Boolean =
    !locked || input == PlayerInput.UnlockButton

/** 首次加载禁用画面手势，顶栏返回入口不受控制层显隐影响。 */
fun backEntryVisible(controlsVisible: Boolean, initialLoading: Boolean): Boolean =
    controlsVisible || initialLoading

/** 播放页上受切集缓冲限制的操作。 */
enum class PlayerAction {
    Back,
    Previous,
    Next,
    PlayPause,
    Seek,
    Volume,
    Lock,
    More,
    Playlist,
    Subtitles,
}

/**
 * 当前可用的播放页操作：首次加载只放行返回；目标加载只放行返回与上一集/下一集；
 * 恢复原片期间额外放行播放/暂停。
 */
fun actionAllowed(
    action: PlayerAction,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
): Boolean = when {
    initialLoading -> action == PlayerAction.Back
    restoring ->
        action == PlayerAction.Back ||
            action == PlayerAction.Previous ||
            action == PlayerAction.Next ||
            action == PlayerAction.PlayPause
    switching ->
        action == PlayerAction.Back ||
            action == PlayerAction.Previous ||
            action == PlayerAction.Next
    else -> true
}
