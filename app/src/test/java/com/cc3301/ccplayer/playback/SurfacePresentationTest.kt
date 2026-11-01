package com.cc3301.ccplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 派生规则的事实组合表：屏上那一帧、视频轨与片尾黑底决定画面与比例。 */
class SurfacePresentationTest {

    private data class Row(
        val screenFrame: ScreenFrame,
        val targetHasVideo: Boolean,
        val holdBlack: Boolean,
        val showsPicture: Boolean,
        val aspectRatio: Double?,
    )

    private val rows = listOf(
        // 屏上没有可沿用的帧：只有黑底，没有比例。
        Row(ScreenFrame.None, targetHasVideo = true, holdBlack = false, showsPicture = false, aspectRatio = null),
        Row(ScreenFrame.None, targetHasVideo = true, holdBlack = true, showsPicture = false, aspectRatio = null),
        Row(ScreenFrame.None, targetHasVideo = false, holdBlack = false, showsPicture = false, aspectRatio = null),
        // 屏上是原片画面：比例锁在原片。
        Row(ScreenFrame.Origin, targetHasVideo = true, holdBlack = false, showsPicture = true, aspectRatio = ORIGIN_RATIO),
        Row(ScreenFrame.Origin, targetHasVideo = true, holdBlack = true, showsPicture = false, aspectRatio = ORIGIN_RATIO),
        Row(ScreenFrame.Origin, targetHasVideo = false, holdBlack = false, showsPicture = false, aspectRatio = ORIGIN_RATIO),
        // 屏上是当前加载的画面：比例换成新文件。
        Row(ScreenFrame.Target, targetHasVideo = true, holdBlack = false, showsPicture = true, aspectRatio = TARGET_RATIO),
        Row(ScreenFrame.Target, targetHasVideo = true, holdBlack = true, showsPicture = false, aspectRatio = TARGET_RATIO),
        Row(ScreenFrame.Target, targetHasVideo = false, holdBlack = false, showsPicture = false, aspectRatio = TARGET_RATIO),
    )

    @Test
    fun `画面与比例按屏上那一帧决定`() {
        for (row in rows) {
            val presentation = deriveSurfacePresentation(
                screenFrame = row.screenFrame,
                targetHasVideo = row.targetHasVideo,
                holdBlack = row.holdBlack,
                originAspectRatio = ORIGIN_RATIO,
                targetAspectRatio = TARGET_RATIO,
            )
            assertEquals(row.toString(), row.showsPicture, presentation.showsPicture)
            assertEquals(row.toString(), row.aspectRatio, presentation.aspectRatio)
        }
    }

    @Test
    fun `比例未知时保持未知，不回退另一个比例`() {
        val origin = deriveSurfacePresentation(
            screenFrame = ScreenFrame.Origin,
            targetHasVideo = true,
            holdBlack = false,
            originAspectRatio = null,
            targetAspectRatio = TARGET_RATIO,
        )
        assertNull(origin.aspectRatio)

        val target = deriveSurfacePresentation(
            screenFrame = ScreenFrame.Target,
            targetHasVideo = true,
            holdBlack = false,
            originAspectRatio = ORIGIN_RATIO,
            targetAspectRatio = null,
        )
        assertNull(target.aspectRatio)
    }

    private companion object {
        const val ORIGIN_RATIO = 16.0 / 9.0
        const val TARGET_RATIO = 9.0 / 16.0
    }
}
