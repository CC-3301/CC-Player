package com.cc3301.ccplayer.ui

/** 双击快退/快进的步长，毫秒。 */
const val SEEK_STEP_MILLIS = 10_000L

/** 播放页上可触摸的元素。 */
enum class PlayerInput {
    /** 点击屏幕显示/隐藏控制条。 */
    Tap,

    /** 三分区双击。 */
    DoubleTap,

    /** 左右半屏上下拖调亮度/音量。 */
    LevelDrag,

    /** 控制条，含其上进度条的拖动。 */
    ControlBar,

    /** 解锁按钮。 */
    UnlockButton,
}

/** 双击的结果：相对当前位置跳转，或切换播放暂停。 */
sealed interface DoubleTapAction {
    /** 相对当前位置跳转 [deltaMillis] 毫秒，负数为快退。 */
    data class Seek(val deltaMillis: Long) : DoubleTapAction

    data object TogglePlayPause : DoubleTapAction
}

/** 上下拖动的目标。 */
enum class DragTarget {
    /** 左半屏：屏幕亮度。 */
    Brightness,

    /** 右半屏：音量。 */
    Volume,
}

/**
 * 双击落点对应的动作：左 1/3 倒退 10 秒，中间 1/3 暂停/继续，右 1/3 前进 10 秒（ADR-0014）。
 * 落在 1/3 分界线上归中间，落在 2/3 分界线上归右侧。
 */
fun doubleTapAction(x: Float, width: Int): DoubleTapAction {
    val third = width / 3f
    return when {
        x < third -> DoubleTapAction.Seek(-SEEK_STEP_MILLIS)
        x < 2 * third -> DoubleTapAction.TogglePlayPause
        else -> DoubleTapAction.Seek(SEEK_STEP_MILLIS)
    }
}

/** 上下拖动的目标：左半屏亮度，右半屏音量（ADR-0014）。落在半屏分界线上归右半屏。 */
fun dragTarget(x: Float, width: Int): DragTarget =
    if (x < width / 2f) DragTarget.Brightness else DragTarget.Volume

/** 锁定时只放行 [PlayerInput.UnlockButton]；其余元素既不响应手势也不显示。 */
fun inputAllowed(input: PlayerInput, locked: Boolean): Boolean =
    !locked || input == PlayerInput.UnlockButton
