package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.ccplayer.settings.OrientationMode

/**
 * 本次播放的方向覆盖。
 *
 * 转屏按钮始终生效（包括设置为“跟随视频”时），只覆盖当前播放，不回写方向设置；
 * 开始或结束播放时清零，同一会话内切集保留（ADR-0025）。
 */
class PlayerSessionOrientation {
    var override by mutableStateOf<ScreenOrientation?>(null)
        private set

    /** 进入新的播放会话：清除上一次播放的转屏结果。 */
    fun beginPlayback() {
        override = null
    }

    /** 退出播放页：清除本次播放的转屏结果。 */
    fun endPlayback() {
        override = null
    }

    /** 用户重新选择方向设置时，让设置立即生效。 */
    fun clearOverride() {
        override = null
    }

    /** 转屏按钮：按已施加的方向或当前屏幕方向切到另一个方向，连点可连续切换。 */
    fun toggle(current: ScreenOrientation) {
        val active = override ?: current
        override = toggledOrientation(active == ScreenOrientation.Landscape)
    }

    /** 本次播放应施加的朝向；没有覆盖时按设置与视频比例判定。 */
    fun resolve(mode: OrientationMode, videoAspectRatio: Double?): ScreenOrientation =
        playerOrientation(mode, videoAspectRatio, override)
}
