package com.cc3301.ccplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.ui.theme.AppColors
import com.cc3301.ccplayer.ui.theme.AppRadius
import com.cc3301.ccplayer.ui.theme.AppSpacing

/** 顶栏：左、右各留一个 44dp 的操作位，标题居中。 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 10.dp),
    ) {
        Box(modifier = Modifier.align(Alignment.CenterStart)) { leading() }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 52.dp),
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** 顶栏图标按钮。 */
@Composable
fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = AppColors.Foreground)
    }
}

/** 方形图标底座：协议入口与空状态共用。 */
@Composable
fun IconTile(
    painter: Painter? = null,
    imageVector: ImageVector? = null,
    tint: Color,
    background: Color,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(if (size >= 64.dp) 20.dp else 14.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        } else if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** 空状态：图标、标题与说明。 */
@Composable
fun EmptyState(
    painter: Painter? = null,
    imageVector: ImageVector? = null,
    tint: Color = AppColors.Accent,
    background: Color = AppColors.AccentSoft,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        IconTile(
            painter = painter,
            imageVector = imageVector,
            tint = tint,
            background = background,
            size = 64.dp,
            iconSize = 30.dp,
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = AppColors.Foreground)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

/** 设置分组卡片：一个表面容器容纳若干行。 */
@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.Accent,
            modifier = Modifier.padding(start = AppSpacing.Xs, bottom = AppSpacing.Sm),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.Lg))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Line, RoundedCornerShape(AppRadius.Lg)),
        ) {
            content()
        }
    }
}
