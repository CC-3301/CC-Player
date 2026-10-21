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
    }

    @Test
    fun `点击播放列表打开抽屉`() {
        panels.toggle(PlayerPanel.Playlist)

        assertTrue(panels.anyOpen)
        assertTrue(panels.playlistOpen)
    }

    @Test
    fun `再次点击播放列表关闭抽屉`() {
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
    }

    @Test
    fun `关闭面板后没有面板打开`() {
        panels.toggle(PlayerPanel.Playlist)

        panels.close()

        assertFalse(panels.anyOpen)
        assertFalse(panels.playlistOpen)
    }

    @Test
    fun `当前项居中所需的滚动量扣除半屏与半个条目`() {
        assertEquals(470, playlistCenterScrollOffset(viewportPx = 1_000, itemPx = 60))
    }

    @Test
    fun `条目高于可视区域时滚动量为零`() {
        assertEquals(0, playlistCenterScrollOffset(viewportPx = 40, itemPx = 60))
    }
}
