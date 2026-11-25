package com.cc3301.ccplayer.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPointerArrowTest {

    private val width = 20f
    private val height = 30f
    private val notchDepth = 12f

    private fun skeleton(tiltDegrees: Float = PLAYER_POINTER_TILT_DEGREES) =
        playerPointerArrowSkeleton(width, height, notchDepth, tiltDegrees)

    /** 底边宽度：两个末端角之间的距离。 */
    private fun PlayerPointerArrowSkeleton.baseWidth(): Float =
        (rightCorner - leftCorner).getDistance()

    /** 箭头轴线：从底边中点到尖端。 */
    private fun axisDirection(s: PlayerPointerArrowSkeleton): Offset =
        s.tip - (s.leftCorner + s.rightCorner) / 2f

    private fun insideTriangle(p: Offset, a: Offset, b: Offset, c: Offset): Boolean {
        fun cross(o: Offset, u: Offset, v: Offset): Float =
            (u.x - o.x) * (v.y - o.y) - (u.y - o.y) * (v.x - o.x)

        val d1 = cross(a, b, p)
        val d2 = cross(b, c, p)
        val d3 = cross(c, a, p)
        val hasNegative = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPositive = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNegative && hasPositive)
    }

    @Test
    fun `轮廓是四点骨架`() {
        val s = skeleton()

        val points = listOf(s.tip, s.leftCorner, s.rightCorner, s.notch)
        assertEquals(4, points.toSet().size)
        assertTrue(points.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test
    fun `V 凹底与两个末端角之间没有平底段`() {
        val s = skeleton(tiltDegrees = 0f)

        // 未倾斜时两个末端角同高，凹底在底边之上：V 的两条边直接从凹底连到末端角。
        assertEquals(s.leftCorner.y, s.rightCorner.y, 0.001f)
        assertTrue(s.notch.y < s.leftCorner.y)
        assertEquals(0f, s.notch.x, 0.001f)
    }

    @Test
    fun `V 凹窄于底边`() {
        val s = skeleton(tiltDegrees = 0f)

        val vEdge = (s.rightCorner - s.notch).getDistance()
        // V 凹两侧的斜边短于底边，凹底不会把底边整条吃掉。
        assertTrue(vEdge < s.baseWidth())
        // 凹底进深浅于底边宽度。
        assertTrue(notchDepth < s.baseWidth())
    }

    @Test
    fun `凹底深入三角形内部`() {
        val s = skeleton()

        assertTrue(insideTriangle(s.notch, s.tip, s.leftCorner, s.rightCorner))
    }

    @Test
    fun `尖端朝左上倾斜约 40 度`() {
        val direction = axisDirection(skeleton())

        assertTrue(direction.x < 0f)
        assertTrue(direction.y < 0f)
        val degreesFromUp = atan2(-direction.x, -direction.y) * 180f / PI.toFloat()
        assertEquals(PLAYER_POINTER_TILT_DEGREES, degreesFromUp, 0.5f)
        assertEquals(40f, PLAYER_POINTER_TILT_DEGREES, 0.001f)
    }

    @Test
    fun `描边宽度约为箭头宽度的百分之七`() {
        // SPEC：描边约为箭头宽度的 7%。
        val stroke = playerPointerArrowStrokeWidth(width)

        assertEquals(0.07f, stroke / width, 0.0001f)
    }

    @Test
    fun `没有倾斜时尖端朝正上方`() {
        val direction = axisDirection(skeleton(tiltDegrees = 0f))

        assertEquals(0f, direction.x, 0.001f)
        assertTrue(direction.y < 0f)
    }
}
