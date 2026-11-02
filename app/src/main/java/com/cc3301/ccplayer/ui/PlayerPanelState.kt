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

    /** 播放列表抽屉打开时隐藏顶栏与底栏，其它面板仍由控制层承载。 */
    val controlBarsVisible: Boolean get() = !playlistOpen

    /** 切换面板：已打开则关闭，否则切换到该面板。 */
    fun toggle(panel: PlayerPanel) {
        openPanel = if (openPanel == panel) null else panel
    }

    /** 关闭当前面板。 */
    fun close() {
        openPanel = null
    }

    /** 面板外点击：有面板打开时关闭并返回 true。 */
    fun dismissOutside(): Boolean {
        if (openPanel == null) return false
        close()
        return true
    }

    /** 选择播放列表条目：先关闭抽屉，再切集。 */
    fun <T> selectPlaylistItem(item: T, onSelect: (T) -> Unit) {
        close()
        onSelect(item)
    }

    /**
     * 控制层不可用（隐藏或锁定）时关闭当前面板。
     *
     * 锁定期间面板不再可见，必须清理状态，否则会继续拦截手势。
     */
    fun closeWhenControlsUnavailable(controlsVisible: Boolean, locked: Boolean) {
        if (!controlsVisible || locked) close()
    }
}

/**
 * 面板是否真正可见：有面板打开，且控制层可见、未锁定。
 *
 * 渲染与手势共用此判定，避免不可见面板仍拦截手势。
 */
fun panelVisible(anyOpen: Boolean, controlsVisible: Boolean, locked: Boolean): Boolean =
    anyOpen && controlsVisible && !locked

/** 播放列表编号：从 1 开始，至少两位。 */
fun playlistItemIndexLabel(index: Int): String = (index + 1).toString().padStart(2, '0')

/** 点抽屉之外的那一半关闭抽屉：横屏点左半屏，竖屏点上半屏；落位由宽高推导。 */
fun playlistDismissTap(tapX: Float, tapY: Float, width: Float, height: Float): Boolean =
    when (playlistDrawerPlacement(width, height)) {
        PlaylistDrawerPlacement.RightHalf -> width > 0f && tapX >= 0f && tapX < width / 2f
        PlaylistDrawerPlacement.BottomHalf -> height > 0f && tapY >= 0f && tapY < height / 2f
    }

/**
 * 把当前项滚到列表可视区域中间所需的内容滚动量。
 *
 * 列表上下各有半个视口的内边距，列表不足一屏时当前项同样能居中；
 * 超出可滚动范围时夹紧到两端。
 */
fun playlistCenterScrollOffset(
    index: Int,
    itemCount: Int,
    viewportPx: Int,
    itemPx: Int,
): Int {
    if (itemCount <= 0 || viewportPx <= 0 || itemPx <= 0) return 0
    val safeIndex = index.coerceIn(0, itemCount - 1)
    val edgePadding = ((viewportPx - itemPx) / 2f).roundToInt().coerceAtLeast(0)
    val contentHeight = itemCount * itemPx + edgePadding * 2
    val maxScroll = (contentHeight - viewportPx).coerceAtLeast(0)
    return (safeIndex * itemPx).coerceIn(0, maxScroll)
}
