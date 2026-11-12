package com.cc3301.ccplayer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 浏览界面顶部的稳定系统栏 inset：一次运行内取采样出现过的最大值，只增不减。
 */
class StableSystemBarInset {
    var value: Dp = 0.dp
        private set

    fun observe(current: Dp) {
        if (current > value) value = current
    }
}

/**
 * 浏览界面的 inset 内边距：顶部按稳定值留白，左右与底部仍随实时 inset 让位。
 * 由根布局采样一次并提供，浏览界面只消费。
 */
@Composable
fun browseInsetPadding(): Modifier {
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    val stable = remember { StableSystemBarInset() }
    stable.observe(with(density) { safeDrawing.getTop(this).toDp() })
    return Modifier
        .windowInsetsPadding(safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(top = stable.value)
}
