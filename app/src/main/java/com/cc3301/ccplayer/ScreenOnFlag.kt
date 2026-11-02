package com.cc3301.ccplayer

import com.cc3301.ccplayer.playback.PlaybackState

/** 屏幕常亮判据：仅前台且播放或加载中为真；暂停、片尾、未打开与退后台都不常亮。 */
internal fun keepScreenOn(foreground: Boolean, state: PlaybackState): Boolean =
    foreground && (state == PlaybackState.Playing || state == PlaybackState.Loading)

/**
 * 屏幕常亮开关：判据见 [keepScreenOn]。
 * 只在结果变化时改写窗口标志，同一状态重复上报不重复下发。
 */
internal class ScreenOnFlag(private val apply: (Boolean) -> Unit) {

    private var enabled = false

    fun update(foreground: Boolean, state: PlaybackState) {
        val keepOn = keepScreenOn(foreground, state)
        if (keepOn == enabled) return
        enabled = keepOn
        apply(keepOn)
    }
}
