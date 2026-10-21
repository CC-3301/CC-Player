package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPanelStateTest {

    private val panels = PlayerPanelState()

    @Test
    fun `默认没有面板打开`() {
        assertFalse(panels.anyOpen)
        assertFalse(panels.playlistOpen)
        assertTrue(panels.controlBarsVisible)
    }

    @Test
    fun `点击播放列表打开抽屉`() {
        panels.toggle(PlayerPanel.Playlist)

        assertTrue(panels.anyOpen)
        assertTrue(panels.playlistOpen)
    }

    @Test
    fun `再次点击播放列表关闭抽屉`() {
        // 抽屉打开时底栏隐藏、按钮不可见；这里只验证状态 API 的切换语义，不是用户路径。
        panels.toggle(PlayerPanel.Playlist)
        panels.toggle(PlayerPanel.Playlist)

        assertFalse(panels.anyOpen)
        assertFalse(panels.playlistOpen)
    }

    @Test
    fun `打开另一个面板时替换当前面板`() {
        panels.toggle(PlayerPanel.More)
        panels.toggle(PlayerPanel.Playlist)

        assertTrue(panels.playlistOpen)
        assertFalse(panels.moreOpen)
        assertFalse(panels.subtitlesOpen)
    }

    @Test
    fun `打开播放列表时隐藏顶栏与底栏`() {
        panels.toggle(PlayerPanel.Playlist)

        assertFalse(panels.controlBarsVisible)
    }

    @Test
    fun `打开其它面板时仍显示顶栏与底栏`() {
        panels.toggle(PlayerPanel.More)
        assertTrue(panels.controlBarsVisible)

        panels.toggle(PlayerPanel.Subtitles)
        assertTrue(panels.controlBarsVisible)
    }

    @Test
    fun `关闭面板后没有面板打开`() {
        panels.toggle(PlayerPanel.Playlist)

        panels.close()

        assertFalse(panels.anyOpen)
        assertFalse(panels.playlistOpen)
        assertTrue(panels.controlBarsVisible)
    }

    @Test
    fun `点左半屏关闭抽屉`() {
        assertTrue(playlistDismissTap(tapX = 0f, width = 1_000f))
        assertTrue(playlistDismissTap(tapX = 499.9f, width = 1_000f))
        assertFalse(playlistDismissTap(tapX = 500f, width = 1_000f))
        assertFalse(playlistDismissTap(tapX = 600f, width = 1_000f))
        assertFalse(playlistDismissTap(tapX = 100f, width = 0f))
    }

    @Test
    fun `更多面板点外部关闭`() {
        panels.toggle(PlayerPanel.More)

        assertTrue(panels.dismissOutside())

        assertFalse(panels.anyOpen)
        assertTrue(panels.controlBarsVisible)
    }

    @Test
    fun `没有面板时外部点击不动作`() {
        assertFalse(panels.dismissOutside())

        assertFalse(panels.anyOpen)
    }

    @Test
    fun `选择列表项时先关闭抽屉再切集`() {
        panels.toggle(PlayerPanel.Playlist)
        var openDuringSelect: Boolean? = null

        panels.selectPlaylistItem("ep1") { openDuringSelect = panels.playlistOpen }

        assertFalse(panels.playlistOpen)
        assertEquals(false, openDuringSelect)
    }

    @Test
    fun `切集或首次加载开始时关闭抽屉`() {
        panels.toggle(PlayerPanel.Playlist)
        panels.closeWhenBlocked(switching = true, initialLoading = false)
        assertFalse(panels.playlistOpen)

        panels.toggle(PlayerPanel.Playlist)
        panels.closeWhenBlocked(switching = false, initialLoading = true)
        assertFalse(panels.playlistOpen)
    }

    @Test
    fun `空闲时保持抽屉打开`() {
        panels.toggle(PlayerPanel.Playlist)

        panels.closeWhenBlocked(switching = false, initialLoading = false)

        assertTrue(panels.playlistOpen)
    }

    @Test
    fun `首项滚动量为零`() {
        assertEquals(
            0,
            playlistCenterScrollOffset(index = 0, itemCount = 10, viewportPx = 1_000, itemPx = 60),
        )
    }

    @Test
    fun `中间项滚动到可视区域中间`() {
        assertEquals(
            240,
            playlistCenterScrollOffset(index = 4, itemCount = 10, viewportPx = 1_000, itemPx = 60),
        )
    }

    @Test
    fun `末项滚动到可滚动范围末端`() {
        assertEquals(
            540,
            playlistCenterScrollOffset(index = 9, itemCount = 10, viewportPx = 1_000, itemPx = 60),
        )
    }

    @Test
    fun `索引超出列表时夹紧到末项`() {
        assertEquals(
            540,
            playlistCenterScrollOffset(index = 99, itemCount = 10, viewportPx = 1_000, itemPx = 60),
        )
    }

    @Test
    fun `列表不足一屏时仍可把末项滚到中间`() {
        assertEquals(
            60,
            playlistCenterScrollOffset(index = 1, itemCount = 2, viewportPx = 1_000, itemPx = 60),
        )
    }

    @Test
    fun `单个条目高于视口时滚动量为零`() {
        assertEquals(
            0,
            playlistCenterScrollOffset(index = 0, itemCount = 1, viewportPx = 40, itemPx = 60),
        )
    }

    @Test
    fun `空列表滚动量为零`() {
        assertEquals(
            0,
            playlistCenterScrollOffset(index = 0, itemCount = 0, viewportPx = 1_000, itemPx = 60),
        )
    }
}
