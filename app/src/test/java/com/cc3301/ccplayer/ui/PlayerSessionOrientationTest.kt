package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSessionOrientationTest {

    private val session = PlayerSessionOrientation()

    @Test
    fun `转屏按钮产生的覆盖优先于方向设置`() {
        session.toggle(ScreenOrientation.Portrait)

        assertEquals(ScreenOrientation.Landscape, session.override)
        assertEquals(
            ScreenOrientation.Landscape,
            session.resolve(OrientationMode.Portrait, 0.56),
        )
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
    fun `转屏覆盖不受视频宽高比影响`() {
        session.toggle(ScreenOrientation.Landscape)

        assertEquals(ScreenOrientation.Portrait, session.resolve(OrientationMode.FollowVideo, 1.78))
        assertEquals(ScreenOrientation.Portrait, session.resolve(OrientationMode.FollowVideo, 0.56))
    }

    @Test
    fun `进入新播放清零转屏覆盖`() {
        session.toggle(ScreenOrientation.Portrait)

        session.beginPlayback()

        assertNull(session.override)
        assertEquals(
            ScreenOrientation.Portrait,
            session.resolve(OrientationMode.Portrait, 1.78),
        )
    }

    @Test
    fun `退出播放页清零转屏覆盖`() {
        session.toggle(ScreenOrientation.Landscape)

        session.endPlayback()

        assertNull(session.override)
        assertEquals(
            ScreenOrientation.Landscape,
            session.resolve(OrientationMode.Landscape, 0.56),
        )
    }

    @Test
    fun `清除覆盖后跟随视频继续按宽高比判定`() {
        session.toggle(ScreenOrientation.Landscape)
        session.clearOverride()

        assertEquals(
            ScreenOrientation.Landscape,
            session.resolve(OrientationMode.FollowVideo, 1.78),
        )
        assertEquals(
            ScreenOrientation.Portrait,
            session.resolve(OrientationMode.FollowVideo, 0.56),
        )
    }
}
