package com.cc3301.ccplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 深色影院主题的取色：背景、表面、边框、文字与强调色。 */
object AppColors {
    val Page = Color(0xFF05070B)
    val Background = Color(0xFF0B0F16)
    val Surface = Color(0xFF141A24)
    val SurfaceHigh = Color(0xFF1A2230)
    val SurfaceHighest = Color(0xFF222C3C)
    val Line = Color(0xFF232D3C)
    val Foreground = Color(0xFFE9EEF7)
    val Muted = Color(0xFF93A0B5)
    val Faint = Color(0xFF6B7789)
    val Accent = Color(0xFF3D8BFD)
    val AccentDeep = Color(0xFF1A73E8)
    val AccentSoft = Color(0xFF152844)
    val Danger = Color(0xFFFF6B6B)
    val DangerSoft = Color(0xFF3A1D22)
    val Ok = Color(0xFF4ADE80)
    val LocalTint = Color(0xFF6EE7A0)
    val LocalSoft = Color(0xFF1D2A1F)
    val WebDavTint = Color(0xFFB197FC)
    val WebDavSoft = Color(0xFF241E33)
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
    background = AppColors.Background,
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

/** 应用主题：替换 Material 默认配色与字号。 */
@Composable
fun CcPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content,
    )
}
