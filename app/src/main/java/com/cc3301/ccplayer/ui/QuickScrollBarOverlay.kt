package com.cc3301.ccplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.cc3301.ccplayer.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * 库页面列表与网格右侧的快速滚动条覆盖层。
 *
 * 条宽 [QuickScrollBarWidth]、最短 [QuickScrollBarMinLength]，贴屏幕右缘；长度与位置按可见范围在
 * 总条目中的比例表达，不显示位置气泡与文字。滚动、按下或拖动时出现，静止
 * [QUICK_SCROLL_BAR_IDLE_MILLIS] 后按 [QUICK_SCROLL_BAR_FADE_OUT_MILLIS] 淡出，出现按
 * [QUICK_SCROLL_BAR_FADE_IN_MILLIS] 淡入；完全透明时不占用右缘 [QuickScrollBarTouchBandWidth]
 * 的触摸带。落在热区内的按下由滚动条接管，按下与拖动都换算成目标行，再滚动到该行的第一项。
 *
 * [firstVisibleIndex] 与 [visibleItemCount] 在组合中读取滚动状态，只让本层跟着滚动重组。
 */
@Composable
fun QuickScrollBarOverlay(
    bar: QuickScrollBarVisibility,
    itemCount: Int,
    rowSize: Int,
    firstVisibleIndex: () -> Int,
    visibleItemCount: () -> Int,
    onLocate: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val barWidthPx = with(density) { QuickScrollBarWidth.toPx() }
    val minThumbHeightPx = with(density) { QuickScrollBarMinLength.toPx() }

    val alpha by animateFloatAsState(
        targetValue = if (bar.visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (bar.visible) {
                QUICK_SCROLL_BAR_FADE_IN_MILLIS
            } else {
                QUICK_SCROLL_BAR_FADE_OUT_MILLIS
            },
        ),
        label = "quickScrollBarAlpha",
    )

    // 静止满 1200ms 后淡出；按住期间不计，滚动与拖动会把起算点往后推。
    LaunchedEffect(bar.visible, bar.lastActivityAtMillis) {
        if (!bar.visible) return@LaunchedEffect
        delay(QUICK_SCROLL_BAR_IDLE_MILLIS)
        bar.hideIfExpired()
    }

    var trackHeightPx by remember { mutableStateOf(0f) }
    val geometry = QuickScrollBarGeometry(
        itemCount = itemCount,
        rowSize = rowSize,
        visibleItemCount = visibleItemCount(),
        trackHeight = trackHeightPx,
        minThumbHeight = minThumbHeightPx,
    )
    val thumbTop = geometry.thumbTop(firstVisibleIndex())
    val latestGeometry = rememberUpdatedState(geometry)

    // 定位只走「滚动到目标项」，不直接改写滚动偏移；连续拖动以最新落点为准。
    var locateTarget by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(locateTarget) {
        val target = locateTarget ?: return@LaunchedEffect
        onLocate(target)
    }

    Box(modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { trackHeightPx = it.height.toFloat() },
        ) {
            if (alpha > 0f) {
                drawRoundRect(
                    color = AppColors.Muted,
                    topLeft = Offset(size.width - barWidthPx, thumbTop),
                    size = Size(barWidthPx, geometry.thumbHeight),
                    cornerRadius = CornerRadius(barWidthPx / 2f),
                    alpha = alpha,
                )
            }
        }
        if (alpha > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(QuickScrollBarTouchBandWidth)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            bar.onPress()
                            locateTarget = latestGeometry.value.itemAt(down.position.y)
                            drag(down.id) { change ->
                                bar.onDrag()
                                locateTarget = latestGeometry.value.itemAt(change.position.y)
                                change.consume()
                            }
                            bar.onRelease()
                        }
                    },
            )
        }
    }
}
