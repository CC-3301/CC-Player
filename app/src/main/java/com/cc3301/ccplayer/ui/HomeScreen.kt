package com.cc3301.ccplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.network.SourceProtocol

/**
 * 首页：左上角设置齿轮，中间标题，下方本地、SMB、WebDAV 三个协议入口。
 */
@Composable
fun HomeScreen(
    onOpenProtocol: (SourceProtocol) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TOP_BAR_HEIGHT)
                .padding(horizontal = 4.dp),
        ) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings_open),
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SourceProtocol.entries.forEach { protocol ->
                ProtocolEntry(protocol = protocol, onClick = { onOpenProtocol(protocol) })
            }
        }
    }
}

@Composable
private fun ProtocolEntry(protocol: SourceProtocol, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 48.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(sourceProtocolIcon(protocol)),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(text = sourceProtocolLabel(protocol), style = MaterialTheme.typography.titleMedium)
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

private fun sourceProtocolIcon(protocol: SourceProtocol): Int = when (protocol) {
    SourceProtocol.Local -> R.drawable.ic_media_folder
    SourceProtocol.Smb -> R.drawable.ic_source_smb
    SourceProtocol.WebDav -> R.drawable.ic_source_webdav
}

private val TOP_BAR_HEIGHT = 64.dp
