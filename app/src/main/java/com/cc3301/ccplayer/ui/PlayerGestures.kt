package com.cc3301.ccplayer.ui

import kotlin.math.abs
import kotlin.math.roundToLong

/** 双击快退/快进的步长，毫秒。 */
const val SEEK_STEP_MILLIS = 10_000L

/** 鼠标滚轮每一格的跳转时长，毫秒。 */
const val WHEEL_SEEK_STEP_MILLIS = 5_000L

/** 中间区域整屏宽度对应的跳转时长，毫秒。 */
const val SEEK_FULL_WIDTH_MILLIS = 300_000L

/** 双击判定窗口，毫秒。 */
const val DOUBLE_TAP_WINDOW_MILLIS = 300L

/** 进入音量调整所需的拖动位移，dp。 */
const val VOLUME_DRAG_THRESHOLD_DP = 9f

/** 左右两侧音量区各占屏宽的比例。 */
const val VOLUME_ZONE_FRACTION = 0.10f

/** 双击分区左右各占屏宽的比例；中间为跳转与暂停区。 */
const val DOUBLE_TAP_ZONE_FRACTION = 0.15f

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

/** 手势显示的提示元素。 */
enum class GestureHint {
    /** 该侧箭头与 3 秒内累计秒数。 */
    DoubleTapSide,

    /** 从该侧屏幕边缘向内扩散的涟漪。 */
    DoubleTapRipple,

    /** 屏幕正中的目标时间与增减量。 */
    SeekTarget,

    /** 音量百分比。 */
    VolumePercent,
}

/** 产生提示的手势。 */
enum class GestureHintSource {
    /** 拖动进度与滚轮调时长。 */
    Seek,

    /** 两侧竖条上下拖动调音量。 */
    Volume,

    /** 中间双击暂停/继续。 */
    DoubleTapPause,

    /** 左右双击 ±10 秒。 */
    DoubleTapSeek,
}

/**
 * 该手势此刻显示的提示元素。控制层隐藏时只保留拖动与滚轮调时长的目标时间、增减量，
 * 以及双击 ±10 秒的该侧箭头与累计秒数；音量百分比与涟漪随控制层一起隐藏，
 * 双击暂停/继续不显示任何元素。
 */
fun shownGestureHints(controlsVisible: Boolean, source: GestureHintSource): Set<GestureHint> = when (source) {
    GestureHintSource.Seek -> setOf(GestureHint.SeekTarget)
    GestureHintSource.Volume -> if (controlsVisible) setOf(GestureHint.VolumePercent) else emptySet()
    GestureHintSource.DoubleTapPause -> emptySet()
    GestureHintSource.DoubleTapSeek ->
        if (controlsVisible) setOf(GestureHint.DoubleTapSide, GestureHint.DoubleTapRipple)
        else setOf(GestureHint.DoubleTapSide)
}

/** 双击对应的提示来源：暂停/继续没有提示元素，±10 秒显示该侧箭头、累计秒数与涟漪。 */
fun doubleTapHintSource(action: DoubleTapAction): GestureHintSource = when (action) {
    is DoubleTapAction.Seek -> GestureHintSource.DoubleTapSeek
    DoubleTapAction.TogglePlayPause -> GestureHintSource.DoubleTapPause
}

/** 拖动进入的手势：音量或跳转。 */
sealed interface DragGesture {
    data object Volume : DragGesture

    data object Seek : DragGesture
}

/**
 * 触点所在分区：左边缘到 10%、右边缘向内 10% 为音量区，其余为中间区。
 * 左 10% 分界线归中间区，右 10% 分界线归右侧音量区。
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

/** 双击落点对应的动作：左侧 15% 倒退 10 秒，中间暂停/继续，右侧 15% 前进 10 秒。 */
fun doubleTapAction(x: Float, width: Int): DoubleTapAction {
    if (width <= 0) return DoubleTapAction.TogglePlayPause
    val edge = width * DOUBLE_TAP_ZONE_FRACTION
    return when {
        x < edge -> DoubleTapAction.Seek(-SEEK_STEP_MILLIS, DoubleTapSide.Left)
        x >= width - edge -> DoubleTapAction.Seek(SEEK_STEP_MILLIS, DoubleTapSide.Right)
        else -> DoubleTapAction.TogglePlayPause
    }
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

/** 滚轮一格的方向：上滚（负位移）后退，下滚前进；没有位移时不动作。 */
fun wheelSeekStep(scrollDeltaY: Float): Long? = when {
    scrollDeltaY > 0f -> WHEEL_SEEK_STEP_MILLIS
    scrollDeltaY < 0f -> -WHEEL_SEEK_STEP_MILLIS
    else -> null
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

/**
 * 顶/底栏是否可见：锁定与抽屉打开时不显示，控制层隐藏时也不显示。
 * 首次缓冲期例外，此时顶/底栏保持可见但只放行返回入口。
 * 缓冲指示与错误提示不属于控制层，不受此判据影响。
 */
fun playerBarsVisible(
    locked: Boolean,
    controlsVisible: Boolean,
    panelBarsVisible: Boolean,
    firstBuffering: Boolean,
): Boolean = inputAllowed(PlayerInput.ControlBar, locked) &&
    (controlsVisible || firstBuffering) &&
    panelBarsVisible

/**
 * 顶/底栏控件此刻是否可以点击：栏可见，或正在淡出（画面还在，点击照常生效）。
 * 退场动画期间不移除可点击的子树，避免控件可见却点不到。
 */
fun playerBarsInteractive(barsVisible: Boolean, exitAnimating: Boolean): Boolean =
    barsVisible || exitAnimating

/** 首次缓冲：本次播放还没有开始过切集时的加载阶段。 */
fun isFirstBuffering(initialLoading: Boolean, switchedSinceOpen: Boolean): Boolean =
    initialLoading && !switchedSinceOpen

/** 本次播放是否开始过切集：开始过之后加载阶段不再算首次缓冲，切集失败回退也算。 */
fun switchedSinceOpen(switching: Boolean, alreadySwitched: Boolean): Boolean =
    alreadySwitched || switching

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
    Rotate,
}

/**
 * 当前可用的播放页操作：首次加载只放行返回；目标加载放行返回、上一集/下一集与播放/暂停
 * （播放/暂停只记意图，切集就绪后按意图执行）；恢复原片期间额外放行播放/暂停。
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
            action == PlayerAction.Next ||
            action == PlayerAction.PlayPause
    else -> true
}

/** 该操作是否还取决于相邻条目：上一集/下一集分别看列表边界。 */
private fun adjacencyAvailable(
    action: PlayerAction,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
): Boolean = when (action) {
    PlayerAction.Previous -> canGoPrevious
    PlayerAction.Next -> canGoNext
    else -> true
}

/**
 * 控件外观是否可用：首次加载只保留返回入口，其余按不可用外观显示；
 * 切集期间与点击判据同源，被拦截的操作一并置灰，不出现能看到却点不动的控件；
 * 恢复原片只拦截点击、不改变亮度（ADR-0025）。上一集/下一集在列表边界始终置灰。
 */
fun actionLooksEnabled(
    action: PlayerAction,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
): Boolean {
    if (initialLoading) return action == PlayerAction.Back
    val adjacency = adjacencyAvailable(action, canGoPrevious, canGoNext)
    if (restoring) return adjacency
    return adjacency && actionAllowed(action, switching, initialLoading, restoring)
}
