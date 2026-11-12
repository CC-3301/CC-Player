package com.cc3301.ccplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.network.SourceProtocol
import com.cc3301.ccplayer.ui.theme.AppColors
import com.cc3301.ccplayer.ui.theme.AppRadius
import com.cc3301.ccplayer.ui.theme.AppSpacing

/**
 * 首页：左上角汉堡按钮打开设置，中间标题，下方本地、SMB、WebDAV 三个协议入口。
 */
@Composable
fun HomeScreen(
    onOpenProtocol: (SourceProtocol) -> Unit,
    onOpenSettings: () -> Unit,
    insetPadding: Modifier,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(insetPadding),
    ) {
        ScreenHeader(
            title = stringResource(R.string.app_name),
            leading = {
                HeaderIconButton(
                    icon = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.settings_open),
                    onClick = onOpenSettings,
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            SourceProtocol.entries.forEach { protocol ->
                ProtocolEntry(protocol = protocol, onClick = { onOpenProtocol(protocol) })
            }
        }
    }
}

@Composable
private fun ProtocolEntry(protocol: SourceProtocol, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AppRadius.Xl)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(AppColors.Surface, AppColors.SurfaceGradientEnd)))
            .border(1.dp, AppColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.Lg, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val tint = protocolTint(protocol)
        IconTile(
            painter = painterResource(sourceProtocolIcon(protocol)),
            tint = tint,
            background = protocolBackground(protocol),
        )
        Text(
            text = sourceProtocolLabel(protocol),
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.size(AppSpacing.Xs))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AppColors.Faint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun sourceProtocolLabel(protocol: SourceProtocol): String = stringResource(
    when (protocol) {
        SourceProtocol.Local -> R.string.source_protocol_local
        SourceProtocol.Smb -> R.string.source_protocol_smb
        SourceProtocol.WebDav -> R.string.source_protocol_webdav
    },
)

fun sourceProtocolIcon(protocol: SourceProtocol): Int = when (protocol) {
    SourceProtocol.Local -> R.drawable.ic_media_folder
    SourceProtocol.Smb -> R.drawable.ic_source_smb
    SourceProtocol.WebDav -> R.drawable.ic_source_webdav
}

fun protocolTint(protocol: SourceProtocol): Color = when (protocol) {
    SourceProtocol.Local -> AppColors.LocalTint
    SourceProtocol.Smb -> AppColors.Accent
    SourceProtocol.WebDav -> AppColors.WebDavTint
}

fun protocolBackground(protocol: SourceProtocol): Color = when (protocol) {
    SourceProtocol.Local -> AppColors.LocalSoft
    SourceProtocol.Smb -> AppColors.AccentSoft
    SourceProtocol.WebDav -> AppColors.WebDavSoft
}
