package com.cc3301.ccplayer.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLayoutTest {

    @Test
    fun `进度条时间贴屏幕左右各 16dp、与轨道间距 16dp`() {
        assertEquals(16.dp, PlayerSeekHorizontalInset)
        assertEquals(16.dp, PlayerSeekTimeGap)
    }

    @Test
    fun `进度条触控高度 36dp、视觉轨道 2dp`() {
        assertEquals(36.dp, PlayerSeekTouchHeight)
        assertEquals(2.dp, PlayerSeekTrackHeight)
    }

    @Test
    fun `播放列表左右内边距与编号到标题间距均为 16dp`() {
        assertEquals(16.dp, PlayerPlaylistHorizontalInset)
        assertEquals(16.dp, PlayerPlaylistIndexGap)
    }

    @Test
    fun `播放列表当前项高亮左右各内缩 4dp`() {
        assertEquals(4.dp, PlayerPlaylistHighlightInset)
    }

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
