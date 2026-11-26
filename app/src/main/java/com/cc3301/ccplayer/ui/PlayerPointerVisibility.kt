package com.cc3301.ccplayer.ui

/**
 * 播放页指针可见性：普通控制层、锁 UI、播放列表与缓冲 UI 显示或淡出时可见，全部移除时同步隐藏。
 * 不设静止隐藏计时或位移唤醒判据；鼠标静止时同样由界面状态直接切换。
 */
fun playerPointerVisible(
    barsPresent: Boolean,
    lockUiPresent: Boolean,
    playlistPresent: Boolean,
    bufferingPresent: Boolean,
): Boolean = barsPresent || lockUiPresent || playlistPresent || bufferingPresent

/**
 * 指针隐藏后的第一次左键只用于唤醒：这一次完整手势及其双击窗口都不产生播放动作。
 *
 * 由鼠标左键的按下与抬起驱动：指针隐藏时的左键按下才算唤醒，指针可见时的按下是普通点击；
 * 唤醒手势抬起后进入 [doubleTapWindowMillis] 窗口，窗口内再次按下同样整次吞掉，但窗口不重新起算。
 * 触摸、滚轮、右键与中键不参与这套判定。
 */
class PlayerPointerWake(
    private val doubleTapWindowMillis: Long = DOUBLE_TAP_WINDOW_MILLIS,
) {
    /** 唤醒手势进行中：从隐藏态左键按下到抬起。 */
    private var waking = false

    /** 双击窗口截止时刻；没有窗口时为 [Long.MIN_VALUE]。 */
    private var swallowUntilMillis = Long.MIN_VALUE

    /**
     * 鼠标左键按下：返回这次按下是否只用于唤醒。只有指针隐藏时的左键按下算唤醒；
     * 指针可见时的按下交给控件执行，不进入唤醒手势。
     */
    fun press(pointerVisible: Boolean, primaryPressed: Boolean): Boolean {
        if (pointerVisible || !primaryPressed) return false
        waking = true
        return true
    }

    /** 左键抬起：从此刻起算这次唤醒手势的双击窗口。 */
    fun onRelease(nowMillis: Long) {
        if (!waking) return
        waking = false
        swallowUntilMillis = nowMillis + doubleTapWindowMillis
    }

    /** 这次按下的完整手势是否只用于唤醒。 */
    fun swallows(nowMillis: Long): Boolean = waking || nowMillis <= swallowUntilMillis
}
