package com.cc3301.ccplayer.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/** 点按状态机的输出：单击显隐控制条，或双击执行分区动作。 */
sealed interface TapGestureAction {
    data object ToggleControls : TapGestureAction

    data class DoubleTap(val action: DoubleTapAction) : TapGestureAction
}

/**
 * 单击/双击状态机：所有判定都由调用方传入的时间驱动，便于用假时钟验证。
 *
 * 第一次松开后进入双击窗口；窗口内第二次按下并松开为双击，窗口结束时仍未有第二次按下则补发单击。
 * 第二次按下若随后变成拖动，调用 [cancel] 明确丢弃本次单击，不在拖动结束后补发。
 */
class PlayerTapGesture(
    private val doubleTapWindowMillis: Long = DOUBLE_TAP_WINDOW_MILLIS,
) {
    private enum class Phase { Idle, Pressed, WaitingSecond, SecondPressed }

    private var phase = Phase.Idle
    private var secondDeadlineMillis = 0L
    private var secondPosition = Offset.Zero

    /** 按下；第二次按下落在窗口外且超时尚未触发时，补发首次单击。 */
    fun press(nowMillis: Long, position: Offset): TapGestureAction? = when (phase) {
        Phase.Idle -> {
            phase = Phase.Pressed
            null
        }

        Phase.WaitingSecond -> {
            if (nowMillis <= secondDeadlineMillis) {
                phase = Phase.SecondPressed
                secondPosition = position
                null
            } else {
                phase = Phase.Pressed
                TapGestureAction.ToggleControls
            }
        }

        Phase.Pressed, Phase.SecondPressed -> null
    }

    /** 松开；第二次按下松开时给出双击动作。 */
    fun release(nowMillis: Long, width: Int): TapGestureAction? = when (phase) {
        Phase.Pressed -> {
            phase = Phase.WaitingSecond
            secondDeadlineMillis = nowMillis + doubleTapWindowMillis
            null
        }

        Phase.SecondPressed -> {
            phase = Phase.Idle
            TapGestureAction.DoubleTap(doubleTapAction(secondPosition.x, width))
        }

        Phase.Idle, Phase.WaitingSecond -> null
    }

    /** 双击窗口到点：仍未等到第二次按下则补发单击。 */
    fun timeout(nowMillis: Long): TapGestureAction? {
        if (phase != Phase.WaitingSecond || nowMillis < secondDeadlineMillis) return null
        phase = Phase.Idle
        return TapGestureAction.ToggleControls
    }

    /** 位移超过 touch slop 或手势层被禁用：明确取消当前点按序列。 */
    fun cancel() {
        phase = Phase.Idle
    }
}

/** 一次拖动位移的结算。 */
data class DragUpdate(
    /** 已识别的手势；尚未超过阈值时为 null。 */
    val gesture: DragGesture?,
    /** 识别后累计的横向位移，用于跳转。 */
    val seekDragX: Float,
    /** 识别后本次的纵向位移，用于音量。 */
    val volumeDragY: Float,
    /** 是否应吞掉本次触摸（已进入拖动或位移超过 touch slop）。 */
    val consume: Boolean,
)

/**
 * 拖动状态机：累计位移超过 [thresholdPx] 后按分区识别音量或跳转。
 * 音量与跳转都只累计识别后的位移；未识别方向超过 [touchSlopPx] 后也吞事件，避免被当成单击。
 */
class PlayerDragGesture(
    private val thresholdPx: Float,
    private val touchSlopPx: Float,
) {
    var gesture: DragGesture? = null
        private set

    private var zone = GestureZone.Center
    private var totalX = 0f
    private var totalY = 0f
    private var appliedX = 0f

    fun start(zone: GestureZone) {
        this.zone = zone
        gesture = null
        totalX = 0f
        totalY = 0f
        appliedX = 0f
    }

    fun update(dx: Float, dy: Float): DragUpdate {
        totalX += dx
        totalY += dy
        if (gesture == null) {
            gesture = dragGesture(zone, totalX, totalY, thresholdPx)
        }
        val current = gesture
        val movedBeyondSlop = abs(totalX) > touchSlopPx || abs(totalY) > touchSlopPx
        if (current == DragGesture.Seek) appliedX += dx
        return DragUpdate(
            gesture = current,
            seekDragX = appliedX,
            volumeDragY = if (current == DragGesture.Volume) dy else 0f,
            consume = current != null || movedBeyondSlop,
        )
    }
}

/**
 * 中间区域拖动跳转：拖动中只更新预览，松手调用 [finish] 才产出提交位置。
 * 时长未知时不预览也不提交。
 */
class PlayerSeekDrag(
    val baseMillis: Long,
    private val durationMillis: Long,
    private val width: Int,
) {
    var previewMillis: Long? = null
        private set

    var committedMillis: Long? = null
        private set

    fun preview(dragX: Float): Long? {
        if (durationMillis <= 0) return null
        val target = (baseMillis + seekDeltaMillis(dragX, width)).coerceIn(0L, durationMillis)
        previewMillis = target
        return target
    }

    /** 松手提交；没有预览时返回 null，表示不跳转。 */
    fun finish(): Long? {
        val target = previewMillis
        previewMillis = null
        committedMillis = target
        return target
    }
}
