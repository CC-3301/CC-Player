package com.cc3301.ccplayer.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cc3301.ccplayer.R
import kotlinx.coroutines.launch

/** 深色影院主题的取色：表面、边框、文字与强调色。 */
object AppColors {
    val Surface = Color(0xFF141A24)
    val SurfaceHigh = Color(0xFF1A2230)
    val SurfaceHighest = Color(0xFF222C3C)
    val SurfaceGradientEnd = Color(0xFF111722)
    val Line = Color(0xFF232D3C)
    val Foreground = Color(0xFFE9EEF7)
    val Muted = Color(0xFF93A0B5)
    val Faint = Color(0xFF6B7789)
    val Accent = Color(0xFF3D8BFD)
    val AccentDeep = Color(0xFF1A73E8)
    val AccentSoft = Color(0xFF152844)
    val Danger = Color(0xFFFF6B6B)
    val DangerSoft = Color(0xFF3A1D22)
    val LocalTint = Color(0xFF6EE7A0)
    val LocalSoft = Color(0xFF1D2A1F)
    val WebDavTint = Color(0xFFB197FC)
    val WebDavSoft = Color(0xFF241E33)
    val SwitchThumb = Color(0xFFC9D3E2)
}

/** 间距令牌。 */
object AppSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 26.dp
}

/** 圆角令牌。 */
object AppRadius {
    val Sm = 8.dp
    val Md = 14.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Fab = 19.dp
    val Sheet = 24.dp
}

private val AppColorScheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color.White,
    primaryContainer = AppColors.AccentSoft,
    onPrimaryContainer = AppColors.Accent,
    secondary = AppColors.Accent,
    onSecondary = Color.White,
    onBackground = AppColors.Foreground,
    surface = AppColors.Surface,
    onSurface = AppColors.Foreground,
    surfaceVariant = AppColors.SurfaceHigh,
    onSurfaceVariant = AppColors.Muted,
    surfaceTint = AppColors.Accent,
    outline = AppColors.Line,
    outlineVariant = AppColors.Line,
    error = AppColors.Danger,
    onError = Color.White,
    errorContainer = AppColors.DangerSoft,
    onErrorContainer = AppColors.Danger,
    scrim = Color(0xFF020509),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    bodyLarge = TextStyle(fontSize = 14.5.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 11.5.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.5.sp),
    labelSmall = TextStyle(fontSize = 10.sp),
)

/** 吞掉按下与悬停反馈，只在键盘/手柄焦点落上时画一层强调色。 */
private object NoPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        NoPressIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}

private class NoPressIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {

    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused) drawRect(color = AppColors.Accent, alpha = FOCUS_ALPHA)
    }

    private companion object {
        const val FOCUS_ALPHA = 0.12f
    }
}

/** Material 组件的按压、拖动与悬停透明，焦点反馈保留。 */
@OptIn(ExperimentalMaterial3Api::class)
private val AppRippleConfiguration = RippleConfiguration(
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0f,
        focusedAlpha = 0.12f,
        hoveredAlpha = 0f,
        pressedAlpha = 0f,
    ),
)

/** 应用主题：替换 Material 默认配色与字号，并关闭点击涟漪；背景以 colors.xml 为唯一来源。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CcPlayerTheme(content: @Composable () -> Unit) {
    val colorScheme = AppColorScheme.copy(background = colorResource(R.color.app_background))
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalIndication provides NoPressIndication,
            LocalRippleConfiguration provides AppRippleConfiguration,
            content = content,
        )
    }
}
