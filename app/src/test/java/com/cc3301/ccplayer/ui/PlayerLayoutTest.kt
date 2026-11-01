package com.cc3301.ccplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.ui.theme.AppColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `播放列表序号与标题均为白色`() {
        assertEquals(Color.White, PlayerPlaylistTextColor)
    }

    @Test
    fun `播放列表当前项底色为深蓝 30 个百分点`() {
        assertEquals(Color(0xFF1A73E8).copy(alpha = 0.30f), PlayerPlaylistCurrentItemColor)
    }

    @Test
    fun `抽屉底色为统一 50 个百分点黑`() {
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

    @Test
    fun `切集期间按冻结画面的原片比例绘制`() {
        // 新文件已上报 2.0：旧画面还在，视频区仍按原片比例，不跟着新文件改。
        assertEquals(
            16.0 / 9.0,
            switchFreezeAspectRatio(videoAspectRatio = 2.0, freezeFrameWidth = 1600, freezeFrameHeight = 900)!!,
            RATIO_TOLERANCE,
        )
    }

    @Test
    fun `切集期间还没有冻结画面时沿用已上报的比例`() {
        assertEquals(
            2.0,
            switchFreezeAspectRatio(videoAspectRatio = 2.0, freezeFrameWidth = 0, freezeFrameHeight = 0)!!,
            RATIO_TOLERANCE,
        )
    }

    @Test
    fun `切集期间没有可用比例时保持未知并交给铺满容器`() {
        assertNull(switchFreezeAspectRatio(videoAspectRatio = null, freezeFrameWidth = 0, freezeFrameHeight = 0))
        assertNull(switchFreezeAspectRatio(videoAspectRatio = null, freezeFrameWidth = -4, freezeFrameHeight = 90))
    }

    @Test
    fun `切集就绪后冻结画面仍盖住视频输出`() {
        // 切集阶段结束不撤帧：覆盖层只看冻结画面在不在。
        assertTrue(showFreezeOverlay(hasFreezeFrame = true, holdBlack = false))
        assertFalse(showFreezeOverlay(hasFreezeFrame = false, holdBlack = false))
    }

    @Test
    fun `片尾黑底不显示冻结画面`() {
        assertFalse(showFreezeOverlay(hasFreezeFrame = true, holdBlack = true))
    }

    @Test
    fun `冻结画面可见期间视频区一直按原片比例`() {
        // 播放核心此时可能已上报新文件的比例：冻结画面还在，视频区不跟着换。
        assertEquals(
            16.0 / 9.0,
            frozenVideoAspectRatio(
                videoAspectRatio = 2.0,
                freezeOverlayVisible = true,
                freezeFrameWidth = 1600,
                freezeFrameHeight = 900,
            )!!,
            RATIO_TOLERANCE,
        )
    }

    @Test
    fun `冻结画面撤掉后视频区换成当前上报的比例`() {
        assertEquals(
            2.0,
            frozenVideoAspectRatio(
                videoAspectRatio = 2.0,
                freezeOverlayVisible = false,
                freezeFrameWidth = 1600,
                freezeFrameHeight = 900,
            )!!,
            RATIO_TOLERANCE,
        )
    }

    @Test
    fun `片尾自动切集沿用屏幕末帧时不铺黑底`() {
        assertFalse(
            showPlayerBlackLayer(
                holdBlack = false,
                buffering = false,
                switching = true,
                hasFreezeFrame = false,
                freezeFramePending = false,
                keepsSurfaceFrame = true,
            ),
        )
    }

    @Test
    fun `切集时没有画面可显示才铺黑底`() {
        // 切集态且没有冻结帧、没有待抓帧、也不沿用末帧，才铺黑底。
        assertTrue(
            showPlayerBlackLayer(
                holdBlack = false,
                buffering = false,
                switching = true,
                hasFreezeFrame = false,
                freezeFramePending = false,
                keepsSurfaceFrame = false,
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

        /** 宽高比按双精度比较。 */
        const val RATIO_TOLERANCE = 0.0001
    }
}
