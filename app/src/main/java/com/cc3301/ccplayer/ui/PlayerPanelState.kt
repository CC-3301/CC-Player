package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/** 播放页可打开的面板；同一时刻只打开一个。 */
enum class PlayerPanel { Playlist, Subtitles, More }

/**
 * 播放页面板互斥状态。
 *
 * 点击同一个面板按钮再次切换时关闭；打开另一个面板时替换当前面板。
 */
class PlayerPanelState {
    var openPanel by mutableStateOf<PlayerPanel?>(null)
        private set

    val anyOpen: Boolean get() = openPanel != null
    val playlistOpen: Boolean get() = openPanel == PlayerPanel.Playlist
    val subtitlesOpen: Boolean get() = openPanel == PlayerPanel.Subtitles
    val moreOpen: Boolean get() = openPanel == PlayerPanel.More

    /** 切换面板：已打开则关闭，否则切换到该面板。 */
    fun toggle(panel: PlayerPanel) {
        openPanel = if (openPanel == panel) null else panel
    }

    /** 关闭当前面板。 */
    fun close() {
        openPanel = null
    }
}

/** 把当前项滚到可视区域中间所需的滚动量；条目高于可视区域时取零。 */
fun playlistCenterScrollOffset(viewportPx: Int, itemPx: Int): Int =
    ((viewportPx - itemPx) / 2f).roundToInt().coerceAtLeast(0)
