package com.cc3301.ccplayer.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 快速滚动条宽。 */
val QuickScrollBarWidth = 6.dp

/** 快速滚动条最短长度。 */
val QuickScrollBarMinLength = 64.dp

/** 快速滚动条的触摸热区宽度：屏幕右缘向内的一条竖带。 */
val QuickScrollBarTouchBandWidth = 32.dp

/** 静止多久后淡出。 */
const val QUICK_SCROLL_BAR_IDLE_MILLIS = 1_200L

/** 淡入时长。 */
const val QUICK_SCROLL_BAR_FADE_IN_MILLIS = 120

/** 淡出时长。 */
const val QUICK_SCROLL_BAR_FADE_OUT_MILLIS = 200

/**
 * 快速滚动条的几何换算。
 *
 * 条长与条位按可见行数在总行数中的比例表达；按下位置先按条长换算成滚动行程的比例，
 * 再换算成行，最后落到行首项。网格按行计算，定位精度是行。
 */
class QuickScrollBarGeometry(
    itemCount: Int,
    private val rowSize: Int,
    visibleItemCount: Int,
    trackHeight: Float,
    minThumbHeight: Float,
) {
    /** 总行数。 */
    val totalRows: Int = ceilRows(itemCount, rowSize)

    /** 可见行数，不超过总行数。 */
    val visibleRows: Int = ceilRows(visibleItemCount, rowSize).coerceIn(1, totalRows)

    /** 条长：可见行数占总行数的比例乘以轨道长度，不短于最短长度，不超出轨道。 */
    val thumbHeight: Float = (trackHeight * visibleRows / totalRows)
        .coerceIn(minThumbHeight.coerceAtMost(trackHeight), trackHeight)

    /** 首行最多能滚到的位置。 */
    private val maxFirstRow: Int = totalRows - visibleRows

    /** 条在轨道内可移动的行程。 */
    private val travel: Float = trackHeight - thumbHeight

    /** 首行对应的条位。 */
    fun thumbTop(firstVisibleIndex: Int): Float {
        if (maxFirstRow == 0) return 0f
        val firstRow = (firstVisibleIndex / rowSize).coerceIn(0, maxFirstRow)
        return travel * firstRow / maxFirstRow
    }

    /** 按下位置（轨道内像素）对应的行。 */
    fun rowAt(pressY: Float): Int {
        if (travel <= 0f) return 0
        val progress = ((pressY - thumbHeight / 2f) / travel).coerceIn(0f, 1f)
        return (progress * maxFirstRow).roundToInt()
    }

    /** 按下位置对应的列表项：网格落在目标行的第一项。 */
    fun itemAt(pressY: Float): Int = rowAt(pressY) * rowSize

    private fun ceilRows(count: Int, size: Int): Int =
        ((count + size - 1) / size).coerceAtLeast(1)
}

/**
 * 快速滚动条的显隐与自动淡出计时。
 *
 * 滚动、按下或拖动时出现；按住期间不淡出；松手后从这一刻重新计时；静止满
 * [QUICK_SCROLL_BAR_IDLE_MILLIS] 后淡出。
 */
class QuickScrollBarVisibility(
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    var visible by mutableStateOf(false)
        private set

    /** 最近一次出现或拖动的时刻：淡出计时以它为准，变化时重新起算。 */
    var lastActivityAtMillis by mutableStateOf(elapsedMillis())
        private set

    private var pressed = false

    fun onScroll() = show()

    fun onDrag() = show()

    fun onPress() {
        pressed = true
        show()
    }

    fun onRelease() {
        pressed = false
        show()
    }

    /** 静止满 [QUICK_SCROLL_BAR_IDLE_MILLIS] 后淡出；按住期间不淡出。 */
    fun hideIfExpired() {
        if (!visible || pressed) return
        if (elapsedMillis() - lastActivityAtMillis >= QUICK_SCROLL_BAR_IDLE_MILLIS) visible = false
    }

    private fun show() {
        visible = true
        lastActivityAtMillis = elapsedMillis()
    }
}
