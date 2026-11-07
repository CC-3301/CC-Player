package com.cc3301.ccplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放页封面层只给纯音频；播放核心把画面（含内嵌封面帧）上屏后让位。 */
class PlayerCoverLayerTest {

    @Test
    fun `纯音频没有画面时显示封面层`() {
        assertTrue(showsPlayerCoverLayer(audioTarget = true, showsPicture = false))
    }

    @Test
    fun `播放核心出图后收起封面层`() {
        assertFalse(showsPlayerCoverLayer(audioTarget = true, showsPicture = true))
    }

    @Test
    fun `视频目标不使用封面层`() {
        assertFalse(showsPlayerCoverLayer(audioTarget = false, showsPicture = false))
        assertFalse(showsPlayerCoverLayer(audioTarget = false, showsPicture = true))
    }
}
