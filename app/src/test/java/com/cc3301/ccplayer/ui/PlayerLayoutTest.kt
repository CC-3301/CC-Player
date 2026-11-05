package com.cc3301.ccplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.ui.theme.AppColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `播放列表当前项高亮左贴边、右侧保留 4dp`() {
        assertEquals(0.dp, PlayerPlaylistHighlightStartInset)
        assertEquals(4.dp, PlayerPlaylistHighlightEndInset)

        val bounds = playlistHighlightBounds(
            rowWidthPx = 1000f,
            startInsetPx = 0f,
            endInsetPx = 4f,
        )

        assertEquals(0f, bounds.start, TOLERANCE)
        assertEquals(996f, bounds.end, TOLERANCE)
    }

    @Test
    fun `行宽不足两侧内缩时高亮两端重合`() {
        val bounds = playlistHighlightBounds(
            rowWidthPx = 6f,
            startInsetPx = 4f,
            endInsetPx = 4f,
        )

        assertEquals(3f, bounds.start, TOLERANCE)
        assertEquals(3f, bounds.end, TOLERANCE)
    }

    @Test
    fun `播放列表与抽屉的配色取值`() {
        // 序号与标题纯白；当前项深蓝三成；抽屉统一半透明黑。
        assertEquals(Color.White, PlayerPlaylistTextColor)
        assertEquals(Color(0xFF1A73E8).copy(alpha = 0.30f), PlayerPlaylistCurrentItemColor)
        assertEquals(Color(0xFF020509).copy(alpha = 0.50f), PlayerPlaylistBackgroundColor)
    }

    @Test
    fun `横屏抽屉占右半屏、竖屏占下半屏`() {
        assertEquals(
            PlaylistDrawerPlacement.RightHalf,
            playlistDrawerPlacement(containerWidth = 2_000f, containerHeight = 1_000f),
        )
        assertEquals(
            PlaylistDrawerPlacement.BottomHalf,
            playlistDrawerPlacement(containerWidth = 1_000f, containerHeight = 2_000f),
        )
    }

    @Test
    fun `宽高相等时按竖屏处理`() {
        assertEquals(
            PlaylistDrawerPlacement.BottomHalf,
            playlistDrawerPlacement(containerWidth = 1_000f, containerHeight = 1_000f),
        )
    }

    @Test
    fun `按压反馈映射：交互控件有涟漪、锁屏与面板外层无涟漪`() {
        val withRipple = PlayerPressTarget.values()
            .filter { playerPressFeedbackEnabled(it) }
            .toSet()
        val withoutRipple = PlayerPressTarget.values()
            .filterNot { playerPressFeedbackEnabled(it) }
            .toSet()

        assertEquals(
            setOf(
                PlayerPressTarget.IconButton,
                PlayerPressTarget.MorePanelRow,
                PlayerPressTarget.AutoPlayNextRow,
                PlayerPressTarget.TextButton,
                PlayerPressTarget.PlaylistRow,
                PlayerPressTarget.SubtitleRow,
            ),
            withRipple,
        )
        assertEquals(
            setOf(
                PlayerPressTarget.LockLayer,
                PlayerPressTarget.OutsideDismissLayer,
            ),
            withoutRipple,
        )
    }

    @Test
    fun `按压涟漪为白色且不透明度 20 个百分点`() {
        assertEquals(Color.White, PlayerPressRippleColor)
        assertEquals(0.2f, PLAYER_PRESS_RIPPLE_ALPHA, TOLERANCE)
    }

    @Test
    fun `缓冲文字 1dp scrim 60 个百分点描边`() {
        assertEquals(1.dp, PlayerBufferingTextStrokeWidth)
        assertEquals(0.60f, PLAYER_BUFFERING_TEXT_SCRIM_ALPHA, TOLERANCE)
    }

    @Test
    fun `缓冲转圈强调色、36dp、粗细 3dp`() {
        assertEquals(AppColors.Accent, PlayerBufferingSpinnerColor)
        assertEquals(36.dp, PlayerBufferingSpinnerSize)
        assertEquals(3.dp, PlayerBufferingSpinnerStrokeWidth)
    }

    @Test
    fun `视频区始终按容器尺寸铺满`() {
        // 尺寸只由容器决定，接口不再接收视频比例；比例与黑边交给 mpv 在 Surface 内处理。
        val portrait = videoViewport(
            containerWidth = 1600f,
            containerHeight = 2560f,
        )
        assertViewport(0f, 0f, 1600f, 2560f, portrait)

        val landscape = videoViewport(
            containerWidth = 2560f,
            containerHeight = 1600f,
        )
        assertViewport(0f, 0f, 2560f, 1600f, landscape)
    }

    @Test
    fun `容器尺寸非正时给出零尺寸矩形`() {
        val viewport = videoViewport(
            containerWidth = 0f,
            containerHeight = -100f,
        )

        assertViewport(0f, 0f, 0f, 0f, viewport)
    }

    @Test
    fun `进页比例未知时视频区铺满容器并被黑底盖住`() {
        val viewport = videoViewport(
            containerWidth = 1600f,
            containerHeight = 2560f,
        )

        // 比例未知期间照常铺满容器，黑底盖住首帧。
        assertViewport(0f, 0f, 1600f, 2560f, viewport)
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = null,
                aspectRatioPlaced = false,
                orientationPending = false,
            ),
        )
    }

    @Test
    fun `比例到达后只撤黑，视频区不再改变尺寸`() {
        val viewport = videoViewport(
            containerWidth = 1600f,
            containerHeight = 2560f,
        )

        assertViewport(0f, 0f, 1600f, 2560f, viewport)
        assertFalse(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = 0.5625,
                aspectRatioPlaced = false,
                orientationPending = false,
            ),
        )
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
