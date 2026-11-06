package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSessionOrientationTest {

    private val session = PlayerSessionOrientation()

    @Test
    fun `转屏按钮按当前横竖状态切到另一个方向`() {
        session.toggle(ScreenOrientation.Portrait)

        assertEquals(ScreenOrientation.Landscape, session.override)
    }

    @Test
    fun `连点转屏按已施加方向连续切换`() {
        // 系统旋转尚未生效时连续点击，仍按本次播放已施加的方向继续切换。
        session.toggle(ScreenOrientation.Portrait)
        assertEquals(ScreenOrientation.Landscape, session.override)

        session.toggle(ScreenOrientation.Portrait)
        assertEquals(ScreenOrientation.Portrait, session.override)

        session.toggle(ScreenOrientation.Portrait)
        assertEquals(ScreenOrientation.Landscape, session.override)
    }

    @Test
    fun `反向横屏下按转屏按钮切到竖屏`() {
        session.toggle(ScreenOrientation.ReverseLandscape)

        assertEquals(ScreenOrientation.Portrait, session.override)
    }

    @Test
    fun `进入新播放清零转屏覆盖`() {
        session.toggle(ScreenOrientation.Portrait)

        session.beginPlayback()

        assertNull(session.override)
    }

    @Test
    fun `退出播放页清零转屏覆盖`() {
        session.toggle(ScreenOrientation.Landscape)

        session.endPlayback()

        assertNull(session.override)
    }

    @Test
    fun `清除覆盖后不再有转屏结果`() {
        session.toggle(ScreenOrientation.Landscape)

        session.clearOverride()

        assertNull(session.override)
    }
}
