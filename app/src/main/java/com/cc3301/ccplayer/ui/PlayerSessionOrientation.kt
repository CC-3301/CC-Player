package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 本次播放的转屏覆盖与会话比例。
 *
 * 转屏按钮始终生效（包括设置为“跟随视频”时），只覆盖当前播放，不回写方向设置；
 * 开始或结束播放时清零，同一会话内切集保留（ADR-0025）。
 *
 * 视频比例跟着会话走：开始新会话时作废，离开播放页保留，回到同一会话沿用它。
 */
class PlayerSessionOrientation {
    var override by mutableStateOf<ScreenOrientation?>(null)
        private set

    /** 本次会话最后已知的视频比例；新会话开始时为空。 */
    var videoAspectRatio by mutableStateOf<Double?>(null)
        private set

    /** 开始新的播放会话：转屏结果与比例都作废。 */
    fun beginPlayback() {
        clearOverride()
        videoAspectRatio = null
    }

    /**
     * 清掉本次播放的转屏结果，比例不受影响。
     *
     * 回到同一会话、离开播放页与重选方向设置都只做这件事。
     */
    fun clearOverride() {
        override = null
    }

    /** 屏上那一帧的比例；有效就记成本次会话最后已知比例。 */
    fun onVideoAspectRatio(videoAspectRatio: Double?) {
        if (hasKnownVideoAspectRatio(videoAspectRatio)) this.videoAspectRatio = videoAspectRatio
    }

    /** 转屏按钮：按已施加的方向或当前屏幕方向切到另一个方向，连点可连续切换。 */
    fun toggle(current: ScreenOrientation) {
        val active = override ?: current
        override = toggledOrientation(active.isLandscape())
    }
}
