package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.settings.OrientationMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进页首次摆位等屏幕方向：只有“跟随视频”档要等，等不到方向时最多等 2 秒就撤黑。
 */
class PlayerOrientationWaitTest {

    @Test
    fun `跟随视频打开横向视频时黑底等到方向对齐才撤`() {
        val ratio = 16.0 / 9.0
        // 首帧与比例都已到达，屏幕还停在竖屏。
        val awaiting = awaitsPlayerOrientation(
            mode = OrientationMode.FollowVideo,
            videoAspectRatio = ratio,
            rotationOverride = null,
            screenOrientation = ScreenOrientation.Portrait,
            showsPicture = true,
            aspectRatioPlaced = false,
        )

        assertTrue(awaiting)
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = ratio,
                aspectRatioPlaced = false,
                orientationPending = awaiting,
            ),
        )

        // 系统转屏完成，onConfigurationChanged 把屏幕方向更新为横屏。
        val alignedAwait = awaitsPlayerOrientation(
            mode = OrientationMode.FollowVideo,
            videoAspectRatio = ratio,
            rotationOverride = null,
            screenOrientation = ScreenOrientation.Landscape,
            showsPicture = true,
            aspectRatioPlaced = false,
        )

        assertFalse(alignedAwait)
        assertFalse(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = ratio,
                aspectRatioPlaced = false,
                orientationPending = alignedAwait,
            ),
        )
    }

    @Test
    fun `跟随视频打开竖向视频时不等方向`() {
        val awaiting = awaitsPlayerOrientation(
            mode = OrientationMode.FollowVideo,
            videoAspectRatio = 9.0 / 16.0,
            rotationOverride = null,
            screenOrientation = ScreenOrientation.Portrait,
            showsPicture = true,
            aspectRatioPlaced = false,
        )

        assertFalse(awaiting)
        assertFalse(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = 9.0 / 16.0,
                aspectRatioPlaced = false,
                orientationPending = awaiting,
            ),
        )
    }

    @Test
    fun `横向视频但屏幕本来就横着时不等方向`() {
        assertFalse(
            awaitsPlayerOrientation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 16.0 / 9.0,
                rotationOverride = null,
                screenOrientation = ScreenOrientation.ReverseLandscape,
                showsPicture = true,
                aspectRatioPlaced = false,
            ),
        )
    }

    @Test
    fun `固定档位不等方向`() {
        // 横屏档遇到竖屏屏幕、竖屏档遇到横屏屏幕、屏幕方向档交回系统，都按各自规则走，不引入等待。
        assertFalse(
            awaitsPlayerOrientation(
                mode = OrientationMode.Landscape,
                videoAspectRatio = 9.0 / 16.0,
                rotationOverride = null,
                screenOrientation = ScreenOrientation.Portrait,
                showsPicture = true,
                aspectRatioPlaced = false,
            ),
        )
        assertFalse(
            awaitsPlayerOrientation(
                mode = OrientationMode.Portrait,
                videoAspectRatio = 16.0 / 9.0,
                rotationOverride = null,
                screenOrientation = ScreenOrientation.Landscape,
                showsPicture = true,
                aspectRatioPlaced = false,
            ),
        )
        assertFalse(
            awaitsPlayerOrientation(
                mode = OrientationMode.System,
                videoAspectRatio = 16.0 / 9.0,
                rotationOverride = null,
                screenOrientation = ScreenOrientation.Portrait,
                showsPicture = true,
                aspectRatioPlaced = false,
            ),
        )
    }

    @Test
    fun `转屏按钮的结果参与方向对齐判定`() {
        // 横向视频被手动转成竖屏：屏幕已在竖屏，不再等。
        assertTrue(
            videoOrientationAligned(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 16.0 / 9.0,
                screenOrientation = ScreenOrientation.Portrait,
                rotationOverride = ScreenOrientation.Portrait,
            ),
        )
        // 手动转成横屏但屏幕还没转过去：继续等。
        assertFalse(
            videoOrientationAligned(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 9.0 / 16.0,
                screenOrientation = ScreenOrientation.Portrait,
                rotationOverride = ScreenOrientation.Landscape,
            ),
        )
    }

    @Test
    fun `比例未知或首帧未到时不等方向`() {
        assertFalse(
            awaitsPlayerOrientation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = null,
                rotationOverride = null,
                screenOrientation = ScreenOrientation.Portrait,
                showsPicture = true,
                aspectRatioPlaced = false,
            ),
        )
        assertFalse(
            awaitsPlayerOrientation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 16.0 / 9.0,
                rotationOverride = null,
                screenOrientation = ScreenOrientation.Portrait,
                showsPicture = false,
                aspectRatioPlaced = false,
            ),
        )
        // 切集沿用屏上旧画面，已经摆过比例就不再等方向。
        assertFalse(
            awaitsPlayerOrientation(
                mode = OrientationMode.FollowVideo,
                videoAspectRatio = 16.0 / 9.0,
                rotationOverride = null,
                screenOrientation = ScreenOrientation.Portrait,
                showsPicture = true,
                aspectRatioPlaced = true,
            ),
        )
    }

    @Test
    fun `方向迟迟不到位时 2 秒后无条件撤黑`() = runTest {
        val ratio = 16.0 / 9.0
        val awaiting = awaitsPlayerOrientation(
            mode = OrientationMode.FollowVideo,
            videoAspectRatio = ratio,
            rotationOverride = null,
            screenOrientation = ScreenOrientation.Portrait,
            showsPicture = true,
            aspectRatioPlaced = false,
        )
        assertTrue(awaiting)

        var expired = false
        launch {
            runOrientationWait()
            expired = true
        }

        advanceTimeBy(PLAYER_ORIENTATION_WAIT_LIMIT_MILLIS - 1)
        runCurrent()
        assertFalse(expired)
        assertTrue(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = ratio,
                aspectRatioPlaced = false,
                orientationPending = awaiting && !expired,
            ),
        )

        advanceTimeBy(1)
        runCurrent()
        assertTrue(expired)
        assertFalse(
            showPlayerBlackLayer(
                showsPicture = true,
                videoAspectRatio = ratio,
                aspectRatioPlaced = false,
                orientationPending = awaiting && !expired,
            ),
        )
    }
}
