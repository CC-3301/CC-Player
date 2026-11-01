package com.cc3301.ccplayer

/**
 * 屏幕常亮开关：仅前台且在播放时为真。
 * 只在结果变化时改写窗口标志，同一状态重复上报不重复下发。
 */
internal class ScreenOnFlag(private val apply: (Boolean) -> Unit) {

    private var enabled = false

    fun update(foreground: Boolean, playing: Boolean) {
        val keepOn = foreground && playing
        if (keepOn == enabled) return
        enabled = keepOn
        apply(keepOn)
    }
}
