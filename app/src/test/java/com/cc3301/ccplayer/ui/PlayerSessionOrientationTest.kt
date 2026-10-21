package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSessionOrientationTest {

    private val session = PlayerSessionOrientation()

    @Test
    fun `转屏按钮覆盖本次播放而不改变设置判定`() {
        session.toggle(isLandscape = false)

        assertEquals(ScreenOrientation.Landscape, session.override)
        assertEquals(
            ScreenOrientation.Landscape,
            session.resolve(OrientationMode.Portrait, 0.56),
        )
    }

    @Test
    fun `同一会话切集保留转屏覆盖`() {
        session.toggle(isLandscape = true)

        // 模拟同一会话内切到不同比例的下一集，覆盖不受宽高比影响。
        assertEquals(ScreenOrientation.Portrait, session.resolve(OrientationMode.FollowVideo, 1.78))
        assertEquals(ScreenOrientation.Portrait, session.resolve(OrientationMode.FollowVideo, 0.56))
    }

    @Test
    fun `进入新播放清零转屏覆盖`() {
        session.toggle(isLandscape = false)

        session.beginPlayback()

        assertNull(session.override)
        assertEquals(
            ScreenOrientation.Portrait,
            session.resolve(OrientationMode.Portrait, 1.78),
        )
    }

    @Test
    fun `退出播放页清零转屏覆盖`() {
        session.toggle(isLandscape = true)

        session.endPlayback()

        assertNull(session.override)
        assertEquals(
            ScreenOrientation.Landscape,
            session.resolve(OrientationMode.Landscape, 0.56),
        )
    }

    @Test
    fun `清除覆盖后跟随视频继续按宽高比判定`() {
        session.toggle(isLandscape = true)
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
