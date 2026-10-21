package com.cc3301.ccplayer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 点按/双击/拖动状态机：用显式时间驱动，覆盖窗口、补发、取消与阈值。 */
class PlayerGestureStateTest {

    private val width = 900
    private val tap = PlayerTapGesture()

    @Test
    fun `单击在双击窗口结束后补发`() {
        assertNull(tap.press(0L, Offset(450f, 0f)))
        assertNull(tap.release(50L, width))

        assertNull(tap.timeout(349L))
        assertEquals(TapGestureAction.ToggleControls, tap.timeout(350L))
    }

    @Test
    fun `窗口内第二次按下松开为双击`() {
        tap.press(0L, Offset(450f, 0f))
        tap.release(50L, width)
        tap.press(300L, Offset(800f, 0f))

        assertEquals(
            TapGestureAction.DoubleTap(DoubleTapAction.Seek(10_000L, DoubleTapSide.Right)),
            tap.release(350L, width),
        )
    }

    @Test
    fun `第二次按下恰在窗口边界仍算双击`() {
        tap.press(0L, Offset(100f, 0f))
        tap.release(50L, width)
        tap.press(350L, Offset(100f, 0f))

        assertEquals(
            TapGestureAction.DoubleTap(DoubleTapAction.Seek(-10_000L, DoubleTapSide.Left)),
            tap.release(400L, width),
        )
    }

    @Test
    fun `超时未触发时第二次按下补发首次单击`() {
        tap.press(0L, Offset(450f, 0f))
        tap.release(50L, width)

        assertEquals(TapGestureAction.ToggleControls, tap.press(400L, Offset(450f, 0f)))
        assertNull(tap.release(450L, width))
        assertEquals(TapGestureAction.ToggleControls, tap.timeout(750L))
    }

    @Test
    fun `第二次按下转拖动明确取消首次单击`() {
        tap.press(0L, Offset(450f, 0f))
        tap.release(50L, width)
        tap.press(100L, Offset(450f, 0f))

        tap.cancel()

        assertNull(tap.timeout(350L))
        assertNull(tap.release(400L, width))
    }

    @Test
    fun `第一次按下转拖动取消单击`() {
        tap.press(0L, Offset(450f, 0f))
        tap.cancel()

        assertNull(tap.release(20L, width))
        assertNull(tap.timeout(300L))
    }

    private val threshold = 9f
    private val slop = 8f

    @Test
    fun `音量只累计超过阈值后的位移`() {
        val drag = PlayerDragGesture(threshold, slop)
        drag.start(GestureZone.LeftVolume)

        val before = drag.update(0f, 8f)
        val crossing = drag.update(0f, 2f)

        assertNull(before.gesture)
        assertFalse(before.consume)
        assertEquals(DragGesture.Volume, crossing.gesture)
        assertEquals(2f, crossing.volumeDragY, 0.0001f)
        assertTrue(crossing.consume)
    }

    @Test
    fun `跳转只累计超过阈值后的位移`() {
        val drag = PlayerDragGesture(threshold, slop)
        drag.start(GestureZone.Center)

        val before = drag.update(8f, 0f)
        val crossing = drag.update(2f, 0f)

        assertNull(before.gesture)
        assertFalse(before.consume)
        assertEquals(DragGesture.Seek, crossing.gesture)
        assertEquals(2f, crossing.seekDragX, 0.0001f)
        assertTrue(crossing.consume)
    }

    @Test
    fun `音量与跳转使用同一阈值`() {
        val volume = PlayerDragGesture(threshold, slop)
        volume.start(GestureZone.RightVolume)
        assertNull(volume.update(0f, 9f).gesture)
        assertEquals(DragGesture.Volume, volume.update(0f, 0.1f).gesture)

        val seek = PlayerDragGesture(threshold, slop)
        seek.start(GestureZone.Center)
        assertNull(seek.update(9f, 0f).gesture)
        assertEquals(DragGesture.Seek, seek.update(0.1f, 0f).gesture)
    }

    @Test
    fun `未识别方向超过 touch slop 也吞事件并取消单击`() {
        val sideHorizontal = PlayerDragGesture(threshold, slop)
        sideHorizontal.start(GestureZone.LeftVolume)
        val sideways = sideHorizontal.update(20f, 0f)

        assertNull(sideways.gesture)
        assertTrue(sideways.consume)

        val centerVertical = PlayerDragGesture(threshold, slop)
        centerVertical.start(GestureZone.Center)
        val vertical = centerVertical.update(0f, 20f)

        assertNull(vertical.gesture)
        assertTrue(vertical.consume)
    }

    @Test
    fun `跳转位移逐次累计`() {
        val drag = PlayerDragGesture(threshold, slop)
        drag.start(GestureZone.Center)
        drag.update(10f, 0f)

        val second = drag.update(5f, 0f)

        assertEquals(DragGesture.Seek, second.gesture)
        assertEquals(15f, second.seekDragX, 0.0001f)
    }

    @Test
    fun `拖动中只更新预览，松手才提交`() {
        val seek = PlayerSeekDrag(baseMillis = 10_000L, durationMillis = 100_000L, width = width)

        assertEquals(100_000L, seek.preview(450f))
        assertEquals(100_000L, seek.previewMillis)
        assertNull(seek.committedMillis)

        assertEquals(100_000L, seek.finish())
        assertNull(seek.previewMillis)
        assertEquals(100_000L, seek.committedMillis)
    }

    @Test
    fun `没有预览时松手不提交`() {
        val seek = PlayerSeekDrag(baseMillis = 10_000L, durationMillis = 100_000L, width = width)

        assertNull(seek.finish())
        assertNull(seek.committedMillis)
    }

    @Test
    fun `时长未知时不预览也不提交`() {
        val seek = PlayerSeekDrag(baseMillis = 10_000L, durationMillis = 0L, width = width)

        assertNull(seek.preview(100f))
        assertNull(seek.previewMillis)
        assertNull(seek.finish())
    }
}
