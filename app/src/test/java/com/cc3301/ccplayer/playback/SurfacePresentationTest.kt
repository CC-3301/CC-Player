package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 派生规则的事实组合表：屏上那一帧、视频轨与片尾黑底决定画面、比例与缓存帧。 */
class SurfacePresentationTest {

    private data class Row(
        val screenFrame: ScreenFrame,
        val targetHasVideo: Boolean,
        val endBlackHold: Boolean,
        val showsPicture: Boolean,
        val aspectRatio: Double?,
    )

    private val rows = listOf(
        // 屏上没有可沿用的帧：只有黑底，没有比例。
        Row(ScreenFrame.None, targetHasVideo = true, endBlackHold = false, showsPicture = false, aspectRatio = null),
        Row(ScreenFrame.None, targetHasVideo = true, endBlackHold = true, showsPicture = false, aspectRatio = null),
        Row(ScreenFrame.None, targetHasVideo = false, endBlackHold = false, showsPicture = false, aspectRatio = null),
        // 屏上是原片画面：比例锁在原片。
        Row(ScreenFrame.Origin, targetHasVideo = true, endBlackHold = false, showsPicture = true, aspectRatio = ORIGIN_RATIO),
        Row(ScreenFrame.Origin, targetHasVideo = true, endBlackHold = true, showsPicture = false, aspectRatio = ORIGIN_RATIO),
        Row(ScreenFrame.Origin, targetHasVideo = false, endBlackHold = false, showsPicture = false, aspectRatio = ORIGIN_RATIO),
        // 屏上是当前加载的画面：比例换成新文件。
        Row(ScreenFrame.Target, targetHasVideo = true, endBlackHold = false, showsPicture = true, aspectRatio = TARGET_RATIO),
        Row(ScreenFrame.Target, targetHasVideo = true, endBlackHold = true, showsPicture = false, aspectRatio = TARGET_RATIO),
        Row(ScreenFrame.Target, targetHasVideo = false, endBlackHold = false, showsPicture = false, aspectRatio = TARGET_RATIO),
    )

    @Test
    fun `画面与比例按屏上那一帧决定`() {
        for (row in rows) {
            val presentation = deriveSurfacePresentation(
                screenFrame = row.screenFrame,
                targetHasVideo = row.targetHasVideo,
                endBlackHold = row.endBlackHold,
                originAspectRatio = ORIGIN_RATIO,
                targetAspectRatio = TARGET_RATIO,
                heldFrame = null,
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
            endBlackHold = false,
            originAspectRatio = null,
            targetAspectRatio = TARGET_RATIO,
            heldFrame = null,
        )
        assertNull(origin.aspectRatio)

        val target = deriveSurfacePresentation(
            screenFrame = ScreenFrame.Target,
            targetHasVideo = true,
            endBlackHold = false,
            originAspectRatio = ORIGIN_RATIO,
            targetAspectRatio = null,
            heldFrame = null,
        )
        assertNull(target.aspectRatio)
    }

    @Test
    fun `缓存帧与画面判据无关，黑底时同样带着`() {
        val frame = testBitmap()

        val black = deriveSurfacePresentation(
            screenFrame = ScreenFrame.None,
            targetHasVideo = false,
            endBlackHold = true,
            originAspectRatio = null,
            targetAspectRatio = null,
            heldFrame = frame,
        )
        assertTrue(black.showsPicture.not())
        assertSame(frame, black.heldFrame)

        val picture = deriveSurfacePresentation(
            screenFrame = ScreenFrame.Target,
            targetHasVideo = true,
            endBlackHold = false,
            originAspectRatio = null,
            targetAspectRatio = TARGET_RATIO,
            heldFrame = frame,
        )
        assertSame(frame, picture.heldFrame)
    }

    /**
     * 测试只需要一个非空帧身份；android.jar 的 Bitmap 构造器在单测里是桩，
     * 用 Unsafe 绕过构造器分配实例。
     */
    private fun testBitmap(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    private companion object {
        const val ORIGIN_RATIO = 16.0 / 9.0
        const val TARGET_RATIO = 9.0 / 16.0
    }
}
