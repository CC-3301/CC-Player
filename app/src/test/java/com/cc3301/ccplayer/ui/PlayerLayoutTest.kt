package com.cc3301.ccplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLayoutTest {

    @Test
    fun `比例未知时铺满容器`() {
        val viewport = videoViewport(
            containerWidth = 1000f,
            containerHeight = 1000f,
            videoAspectRatio = null,
        )

        assertViewport(0f, 0f, 1000f, 1000f, viewport)
    }

    @Test
    fun `横视频按宽度贴合并在垂直方向居中`() {
        val viewport = videoViewport(
            containerWidth = 1000f,
            containerHeight = 1000f,
            videoAspectRatio = 2.0,
        )

        assertViewport(0f, 250f, 1000f, 500f, viewport)
    }

    @Test
    fun `竖视频按高度贴合并在水平方向居中`() {
        val viewport = videoViewport(
            containerWidth = 1000f,
            containerHeight = 1000f,
            videoAspectRatio = 0.5,
        )

        assertViewport(250f, 0f, 500f, 1000f, viewport)
    }

    @Test
    fun `正方形视频取较短边`() {
        val viewport = videoViewport(
            containerWidth = 1000f,
            containerHeight = 400f,
            videoAspectRatio = 1.0,
        )

        assertViewport(300f, 0f, 400f, 400f, viewport)
    }

    @Test
    fun `比例非正或非有限时铺满容器`() {
        listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { aspect ->
            val viewport = videoViewport(
                containerWidth = 1000f,
                containerHeight = 500f,
                videoAspectRatio = aspect,
            )

            assertViewport(0f, 0f, 1000f, 500f, viewport)
        }
    }

    @Test
    fun `容器尺寸非正时给出零尺寸矩形`() {
        val viewport = videoViewport(
            containerWidth = 0f,
            containerHeight = -100f,
            videoAspectRatio = 1.0,
        )

        assertViewport(0f, 0f, 0f, 0f, viewport)
    }

    private fun assertViewport(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        viewport: VideoViewport,
    ) {
        assertEquals(left, viewport.left, TOLERANCE)
        assertEquals(top, viewport.top, TOLERANCE)
        assertEquals(width, viewport.width, TOLERANCE)
        assertEquals(height, viewport.height, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
