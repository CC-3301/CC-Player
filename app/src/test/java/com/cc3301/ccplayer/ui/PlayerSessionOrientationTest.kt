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
    fun `离开播放页只清转屏结果`() {
        session.toggle(ScreenOrientation.Landscape)

        session.clearOverride()

        assertNull(session.override)
    }

    @Test
    fun `清除覆盖后不再有转屏结果`() {
        session.toggle(ScreenOrientation.Landscape)

        session.clearOverride()

        assertNull(session.override)
    }

    @Test
    fun `新会话不沿用上一次的比例`() {
        session.onVideoAspectRatio(1.78)

        session.beginPlayback()

        assertNull(session.videoAspectRatio)
    }

    @Test
    fun `离开播放页保留比例回到同一会话沿用`() {
        session.onVideoAspectRatio(0.5625)

        // 离开页面与回到同一会话都只清转屏结果。
        session.clearOverride()
        assertEquals(0.5625, session.videoAspectRatio!!, 0.0)

        session.toggle(ScreenOrientation.Portrait)
        session.clearOverride()
        assertEquals(0.5625, session.videoAspectRatio!!, 0.0)
    }

    @Test
    fun `无效比例不覆盖已记录的比例`() {
        session.onVideoAspectRatio(1.78)

        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { ratio ->
            session.onVideoAspectRatio(ratio)
            assertEquals("比例 $ratio", 1.78, session.videoAspectRatio!!, 0.0)
        }
    }
}
