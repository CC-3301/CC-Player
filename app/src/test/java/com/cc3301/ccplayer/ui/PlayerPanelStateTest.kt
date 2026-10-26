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
    fun `横屏点左半屏关闭抽屉`() {
        assertTrue(playlistDismissTap(tapX = 0f, tapY = 0f, width = 2_000f, height = 1_000f))
        assertTrue(playlistDismissTap(tapX = 999.9f, tapY = 500f, width = 2_000f, height = 1_000f))
        assertFalse(playlistDismissTap(tapX = 1_000f, tapY = 0f, width = 2_000f, height = 1_000f))
        assertFalse(playlistDismissTap(tapX = 1_200f, tapY = 0f, width = 2_000f, height = 1_000f))
    }

    @Test
    fun `竖屏点上半屏关闭抽屉`() {
        assertTrue(playlistDismissTap(tapX = 500f, tapY = 0f, width = 1_000f, height = 2_000f))
        assertTrue(playlistDismissTap(tapX = 1_000f, tapY = 999.9f, width = 1_000f, height = 2_000f))
        assertFalse(playlistDismissTap(tapX = 0f, tapY = 1_000f, width = 1_000f, height = 2_000f))
        assertFalse(playlistDismissTap(tapX = 0f, tapY = 1_200f, width = 1_000f, height = 2_000f))
    }

    @Test
    fun `容器尺寸为零时不关闭抽屉`() {
        assertFalse(playlistDismissTap(tapX = 0f, tapY = 0f, width = 0f, height = 0f))
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
    fun `面板可见性真值表`() {
        assertTrue(panelVisible(anyOpen = true, controlsVisible = true, locked = false))
        assertFalse(panelVisible(anyOpen = true, controlsVisible = false, locked = false))
        assertFalse(panelVisible(anyOpen = true, controlsVisible = true, locked = true))
        assertFalse(panelVisible(anyOpen = true, controlsVisible = false, locked = true))
        assertFalse(panelVisible(anyOpen = false, controlsVisible = true, locked = false))
        assertFalse(panelVisible(anyOpen = false, controlsVisible = false, locked = false))
        assertFalse(panelVisible(anyOpen = false, controlsVisible = true, locked = true))
    }

    @Test
    fun `控制层隐藏时关闭已打开的面板并恢复手势`() {
        panels.toggle(PlayerPanel.More)
        val visibleBeforeHide = panelVisible(panels.anyOpen, controlsVisible = true, locked = false)
        assertTrue(visibleBeforeHide)
        assertFalse(gesturesAllowed(locked = false, panelOpen = visibleBeforeHide))

        // 面板尚未清理时，隐藏后的可见性判定已放行手势。
        val hiddenBeforeCleanup = panelVisible(panels.anyOpen, controlsVisible = false, locked = false)
        assertFalse(hiddenBeforeCleanup)
        assertTrue(gesturesAllowed(locked = false, panelOpen = hiddenBeforeCleanup))

        panels.closeWhenControlsUnavailable(controlsVisible = false, locked = false)

        assertFalse(panels.anyOpen)
        val hiddenAfterCleanup = panelVisible(panels.anyOpen, controlsVisible = false, locked = false)
        assertFalse(hiddenAfterCleanup)
        assertTrue(gesturesAllowed(locked = false, panelOpen = hiddenAfterCleanup))
    }

    @Test
    fun `控制层可见时保持面板打开`() {
        panels.toggle(PlayerPanel.Subtitles)

        panels.closeWhenControlsUnavailable(controlsVisible = true, locked = false)

        assertTrue(panels.anyOpen)
        assertTrue(panels.subtitlesOpen)
        assertTrue(panelVisible(panels.anyOpen, controlsVisible = true, locked = false))
    }

    @Test
    fun `锁定时关闭已打开的面板`() {
        panels.toggle(PlayerPanel.Playlist)
        assertFalse(panelVisible(panels.anyOpen, controlsVisible = true, locked = true))

        panels.closeWhenControlsUnavailable(controlsVisible = true, locked = true)

        assertFalse(panels.anyOpen)
    }

    @Test
    fun `反复隐藏显示控制层后不留下面板状态`() {
        PlayerPanel.entries.forEach { panel ->
            repeat(3) {
                panels.toggle(panel)
                val visibleWhileShown = panelVisible(panels.anyOpen, controlsVisible = true, locked = false)
                assertTrue(visibleWhileShown)
                assertFalse(gesturesAllowed(locked = false, panelOpen = visibleWhileShown))

                panels.closeWhenControlsUnavailable(controlsVisible = true, locked = false)
                assertTrue(panels.anyOpen)

                panels.closeWhenControlsUnavailable(controlsVisible = false, locked = false)
                assertFalse(panels.anyOpen)
                val visibleAfterHide = panelVisible(panels.anyOpen, controlsVisible = false, locked = false)
                assertFalse(visibleAfterHide)
                assertTrue(gesturesAllowed(locked = false, panelOpen = visibleAfterHide))
            }
        }
    }

    @Test
    fun `播放列表编号至少两位`() {
        assertEquals("01", playlistItemIndexLabel(0))
        assertEquals("09", playlistItemIndexLabel(8))
        assertEquals("10", playlistItemIndexLabel(9))
        assertEquals("99", playlistItemIndexLabel(98))
        assertEquals("100", playlistItemIndexLabel(99))
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
