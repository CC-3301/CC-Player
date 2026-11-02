package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** 双击累计提示的保留时长，毫秒。 */
const val DOUBLE_TAP_FEEDBACK_MILLIS = 3_000L

/** 一侧的双击提示：窗口内累计时长（毫秒）与最后一次双击时刻。 */
data class DoubleTapHintState(
    val accumulatedMillis: Long,
    val updatedAtMillis: Long,
)

/** 一次双击产生的边缘涟漪。 */
data class DoubleTapRipple(val id: Long, val side: DoubleTapSide)

/**
 * 双击提示状态：左右两侧各自累计，3 秒内继续双击累加，超时清除。
 * 每次双击都产生新的涟漪编号，供界面重播扩散动画。
 */
class PlayerDoubleTapFeedback(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var left by mutableStateOf<DoubleTapHintState?>(null)
        private set

    var right by mutableStateOf<DoubleTapHintState?>(null)
        private set

    /** 当前仍在播放涟漪的条目；每条动画结束后由界面按编号移除。 */
    var ripples by mutableStateOf<List<DoubleTapRipple>>(emptyList())
        private set

    private var nextRippleId = 1L

    /** 记录一次双击；[ripple] 为假时只累计秒数，不产生边缘涟漪。 */
    fun onDoubleTap(side: DoubleTapSide, deltaMillis: Long, ripple: Boolean = true) {
        val now = elapsedMillis()
        expire(now)
        val previous = hint(side)
        val accumulated = (previous?.accumulatedMillis ?: 0L) + abs(deltaMillis)
        setHint(side, DoubleTapHintState(accumulated, now))
        if (ripple) {
            ripples = ripples + DoubleTapRipple(nextRippleId, side)
            nextRippleId += 1
        }
    }

    /** 一条涟漪动画结束后移除；不影响其它仍在扩散的涟漪。 */
    fun removeRipple(id: Long) {
        ripples = ripples.filterNot { it.id == id }
    }

    /** 涟漪层不再可见时清掉未播完的条目：重新显示时不重播。 */
    fun clearRipples() {
        if (ripples.isNotEmpty()) ripples = emptyList()
    }

    /** 切集时清空提示与涟漪，避免沿用上一集累计。 */
    fun reset() {
        left = null
        right = null
        ripples = emptyList()
    }

    /** 清除超过窗口仍未再次双击的提示。 */
    fun expire(nowMillis: Long = elapsedMillis()) {
        if (left?.let { nowMillis - it.updatedAtMillis > DOUBLE_TAP_FEEDBACK_MILLIS } == true) {
            left = null
        }
        if (right?.let { nowMillis - it.updatedAtMillis > DOUBLE_TAP_FEEDBACK_MILLIS } == true) {
            right = null
        }
    }

    /** 距最早一侧提示超时的毫秒数；没有提示时为 null。 */
    fun millisUntilExpire(): Long? {
        val now = elapsedMillis()
        return listOfNotNull(left, right)
            .minOfOrNull { it.updatedAtMillis + DOUBLE_TAP_FEEDBACK_MILLIS + 1L - now }
            ?.coerceAtLeast(0L)
    }

    private fun hint(side: DoubleTapSide): DoubleTapHintState? =
        if (side == DoubleTapSide.Left) left else right

    /** 控制层是否在场：在场（含淡出中）时保留涟漪，离开后才清理。 */
    fun syncRipples(controlsPresent: Boolean) {
        if (!controlsPresent) clearRipples()
    }

    private fun setHint(side: DoubleTapSide, state: DoubleTapHintState) {
        if (side == DoubleTapSide.Left) left = state else right = state
    }
}
