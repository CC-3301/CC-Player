package com.cc3301.ccplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 源列表：只显示连接源名称，右下角加号新增；网络源可从条目的更多菜单编辑或删除。
 */
@Composable
fun SourceListScreen(
    protocol: SourceProtocol,
    state: SourcesState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpenSource: (ConnectionSource) -> Unit,
    onEditSource: (ConnectionSource) -> Unit,
    onDeleteSource: (ConnectionSource) -> Unit,
    onFormFieldChange: (NetworkSourceFieldKind, String) -> Unit,
    onDismissForm: () -> Unit,
    onSaveForm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ConnectionSource?>(null) }
    val sources = state.sources(protocol)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = sourceProtocolLabel(protocol),
                leading = {
                    HeaderIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.library_back),
                        onClick = onBack,
                    )
                },
            )
            when {
                !state.ready -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppColors.Accent)
                }

                sources.isEmpty() -> EmptyState(
                    painter = painterResource(sourceProtocolIcon(protocol)),
                    tint = protocolTint(protocol),
                    background = protocolBackground(protocol),
                    title = stringResource(emptyTitle(protocol)),
                    message = stringResource(emptyMessage(protocol)),
                    modifier = Modifier.fillMaxSize(),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AppSpacing.Lg,
                        end = AppSpacing.Lg,
                        top = AppSpacing.Sm,
                        bottom = 96.dp,
                    ),
                ) {
                    items(sources, key = { it.location }) { source ->
                        SourceRow(
                            protocol = protocol,
                            source = source,
                            onOpen = { onOpenSource(source) },
                            onEdit = { onEditSource(source) },
                            onDelete = { pendingDelete = source },
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            containerColor = AppColors.AccentDeep,
            contentColor = Color.White,
            shape = RoundedCornerShape(AppRadius.Fab),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(AppSpacing.Xl),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(R.string.sources_add))
        }
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = AppColors.Surface,
            titleContentColor = AppColors.Foreground,
            textContentColor = AppColors.Muted,
            shape = RoundedCornerShape(AppRadius.Sheet),
            title = { Text(text = stringResource(R.string.sources_delete_title)) },
            text = {
                Text(
                    text = stringResource(
                        if (source.protocol == SourceProtocol.Local) {
                            R.string.sources_delete_local_message
                        } else {
                            R.string.sources_delete_remote_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteSource(source)
                    },
                ) {
                    Text(text = stringResource(R.string.sources_delete_confirm), color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.sources_cancel), color = AppColors.Muted)
                }
            },
        )
    }

    state.form?.let { form ->
        val protocolName = sourceProtocolLabel(form.protocol)
        NetworkSourceDialog(
            title = stringResource(
                if (form.editing) R.string.network_source_edit_title else R.string.network_source_add_title,
                protocolName,
            ),
            fields = networkSourceFields(form),
            saving = form.saving,
            addressInvalid = form.addressInvalid,
            duplicateAddress = form.duplicateAddress,
            failure = form.failure,
            onFieldChange = onFormFieldChange,
            onDismiss = onDismissForm,
            onConfirm = onSaveForm,
        )
    }
}

@Composable
private fun SourceRow(
    protocol: SourceProtocol,
    source: ConnectionSource,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AppRadius.Lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Xs)
            .clip(shape)
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Line, shape)
            .clickable(onClick = onOpen)
            .padding(start = 13.dp, end = 10.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            painter = painterResource(sourceProtocolIcon(protocol)),
            tint = protocolTint(protocol),
            background = protocolBackground(protocol),
            size = 40.dp,
            iconSize = 22.dp,
        )
        Text(
            text = source.name,
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.Md),
        )
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.sources_menu),
                    tint = AppColors.Muted,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = AppColors.SurfaceHigh,
                shape = RoundedCornerShape(AppRadius.Md),
            ) {
                if (source.editable) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.sources_edit)) },
                        onClick = {
                            expanded = false
                            onEdit()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.sources_delete), color = AppColors.Danger) },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 表单字段按协议给出；状态更新按字段本身分派，不依赖列表下标。 */
@Composable
private fun networkSourceFields(form: NetworkSourceForm): List<NetworkSourceField> {
    val name = NetworkSourceField(NetworkSourceFieldKind.Name, stringResource(R.string.network_source_name), form.name)
    val address = NetworkSourceField(
        kind = NetworkSourceFieldKind.Address,
        label = stringResource(R.string.network_source_server_address),
        value = form.address,
        required = true,
    )
    val username = NetworkSourceField(NetworkSourceFieldKind.Username, stringResource(R.string.network_source_username), form.username)
    val password = NetworkSourceField(
        kind = NetworkSourceFieldKind.Password,
        label = stringResource(R.string.network_source_password),
        value = form.password,
        secret = true,
    )
    return when (form.protocol) {
        SourceProtocol.Smb -> listOf(
            name,
            address,
            NetworkSourceField(NetworkSourceFieldKind.Path, stringResource(R.string.network_source_share_path), form.path),
            username,
            password,
        )

        SourceProtocol.WebDav -> listOf(
            name,
            address,
            NetworkSourceField(NetworkSourceFieldKind.Path, stringResource(R.string.network_source_start_directory), form.path),
            username,
            password,
        )

        SourceProtocol.Local -> emptyList()
    }
}

private fun emptyTitle(protocol: SourceProtocol): Int = when (protocol) {
    SourceProtocol.Local -> R.string.sources_empty_title_local
    SourceProtocol.Smb -> R.string.sources_empty_title_smb
    SourceProtocol.WebDav -> R.string.sources_empty_title_webdav
}

private fun emptyMessage(protocol: SourceProtocol): Int = when (protocol) {
    SourceProtocol.Local -> R.string.sources_empty_local
    SourceProtocol.Smb -> R.string.sources_empty_smb
    SourceProtocol.WebDav -> R.string.sources_empty_webdav
}
