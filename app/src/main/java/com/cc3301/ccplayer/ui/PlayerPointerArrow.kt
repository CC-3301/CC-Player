package com.cc3301.ccplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 自绘箭头宽度，dp：两个末端角之间的距离。 */
private val PlayerPointerArrowWidth = 20.dp

/** 箭头高度与宽度之比：尖端到两个末端角连线的距离。 */
private const val PLAYER_POINTER_ARROW_HEIGHT_RATIO = 1.5f

/** V 凹进深与箭头宽度之比：进深浅于底边，凹底落在三角形内部。 */
private const val PLAYER_POINTER_NOTCH_DEPTH_RATIO = 0.6f

/** 尖端朝左上的倾斜角度，度。 */
const val PLAYER_POINTER_TILT_DEGREES = 40f

/** 描边宽度与箭头宽度之比。 */
private const val PLAYER_POINTER_STROKE_RATIO = 0.07f

/** 箭头填充色。 */
private val PlayerPointerArrowFill = Color.White

/** 箭头描边色。 */
private val PlayerPointerArrowStroke = Color.Black

/**
 * 四点骨架的箭头轮廓：尖端、左右两个末端角与 V 凹底。
 * 坐标原点为未倾斜时的包围盒中心，四点整体绕原点旋转。
 * 底边是两个末端角的连线；凹底向三角形内部凹进，两条 V 边直接从凹底连到末端角，中间没有平底段。
 */
data class PlayerPointerArrowSkeleton(
    val tip: Offset,
    val leftCorner: Offset,
    val rightCorner: Offset,
    val notch: Offset,
)

/**
 * 按宽度、高度、V 凹进深与倾斜角算出四点骨架。未倾斜时尖端朝正上方，
 * 两个末端角同高、凹底在中线上且高于底边；倾斜角为屏幕上逆时针方向，尖端朝左上。
 */
fun playerPointerArrowSkeleton(
    width: Float,
    height: Float,
    notchDepth: Float,
    tiltDegrees: Float,
): PlayerPointerArrowSkeleton {
    val halfWidth = width / 2f
    val halfHeight = height / 2f
    return PlayerPointerArrowSkeleton(
        tip = Offset(0f, -halfHeight).rotateOnScreen(tiltDegrees),
        leftCorner = Offset(-halfWidth, halfHeight).rotateOnScreen(tiltDegrees),
        rightCorner = Offset(halfWidth, halfHeight).rotateOnScreen(tiltDegrees),
        notch = Offset(0f, halfHeight - notchDepth).rotateOnScreen(tiltDegrees),
    )
}

/** 屏幕坐标（y 向下）里按逆时针方向旋转。 */
private fun Offset.rotateOnScreen(degrees: Float): Offset {
    val radians = degrees / 180f * PI.toFloat()
    val cosine = cos(radians)
    val sine = sin(radians)
    return Offset(x * cosine + y * sine, -x * sine + y * cosine)
}

/**
 * 播放页自绘箭头：白填充、深色描边、转角圆润；只绘制，不接收任何输入。
 * [position] 在绘制阶段取值，鼠标移动只重绘箭头，不触发组合。
 */
@Composable
fun PlayerPointerArrow(position: () -> Offset?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val widthPx = with(density) { PlayerPointerArrowWidth.toPx() }
    Canvas(modifier = modifier) {
        val at = position() ?: return@Canvas
        drawPlayerPointerArrow(
            skeleton = playerPointerArrowSkeleton(
                width = widthPx,
                height = widthPx * PLAYER_POINTER_ARROW_HEIGHT_RATIO,
                notchDepth = widthPx * PLAYER_POINTER_NOTCH_DEPTH_RATIO,
                tiltDegrees = PLAYER_POINTER_TILT_DEGREES,
            ),
            position = at,
            strokeWidth = widthPx * PLAYER_POINTER_STROKE_RATIO,
        )
    }
}

/** 以尖端为热点绘制箭头；轮廓顺序为尖端、右末端角、V 凹底、左末端角。 */
private fun DrawScope.drawPlayerPointerArrow(
    skeleton: PlayerPointerArrowSkeleton,
    position: Offset,
    strokeWidth: Float,
) {
    val offset = position - skeleton.tip
    val path = Path().apply {
        moveTo(skeleton.tip.x + offset.x, skeleton.tip.y + offset.y)
        lineTo(skeleton.rightCorner.x + offset.x, skeleton.rightCorner.y + offset.y)
        lineTo(skeleton.notch.x + offset.x, skeleton.notch.y + offset.y)
        lineTo(skeleton.leftCorner.x + offset.x, skeleton.leftCorner.y + offset.y)
        close()
    }
    drawPath(path = path, color = PlayerPointerArrowFill, style = Fill)
    drawPath(
        path = path,
        color = PlayerPointerArrowStroke,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}
