package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** 冻结画面裁切：缩略图接口给正方形，按视频宽高比取中心区域。 */
class VideoFrameCropTest {

    @Test
    fun `wide video keeps full width and crops top and bottom`() {
        val crop = freezeFrameCrop(
            sourceWidth = 1920,
            sourceHeight = 1920,
            videoWidth = 1920,
            videoHeight = 1080,
        )

        assertEquals(FrameCrop(0, 420, 1920, 1080), crop)
    }

    @Test
    fun `tall video keeps full height and crops both sides`() {
        val crop = freezeFrameCrop(
            sourceWidth = 1920,
            sourceHeight = 1920,
            videoWidth = 1080,
            videoHeight = 1920,
        )

        assertEquals(FrameCrop(420, 0, 1080, 1920), crop)
    }

    @Test
    fun `square video keeps the whole frame`() {
        val crop = freezeFrameCrop(
            sourceWidth = 1920,
            sourceHeight = 1920,
            videoWidth = 720,
            videoHeight = 720,
        )

        assertEquals(FrameCrop(0, 0, 1920, 1920), crop)
    }

    @Test
    fun `unknown video size keeps the grabbed frame`() {
        val crop = freezeFrameCrop(
            sourceWidth = 1920,
            sourceHeight = 1920,
            videoWidth = 0,
            videoHeight = 0,
        )

        assertEquals(FrameCrop(0, 0, 1920, 1920), crop)
    }

    @Test
    fun `crop keeps at least one pixel and stays centered`() {
        val crop = freezeFrameCrop(
            sourceWidth = 10,
            sourceHeight = 10,
            videoWidth = 100,
            videoHeight = 1,
        )

        assertEquals(FrameCrop(0, 4, 10, 1), crop)
    }
}
