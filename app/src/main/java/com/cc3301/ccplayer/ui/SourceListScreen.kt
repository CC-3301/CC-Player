package com.cc3301.ccplayer.ui

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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.network.SourceProtocol

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
    onFormFieldChange: (Int, String) -> Unit,
    onDismissForm: () -> Unit,
    onSaveForm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ConnectionSource?>(null) }
    val sources = state.sources(protocol)

    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(R.string.library_back))
                }
                Text(
                    text = sourceProtocolLabel(protocol),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
            }
            when {
                !state.ready -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                sources.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(emptyMessage(protocol)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(sources, key = { it.location }) { source ->
                        SourceRow(
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
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(R.string.sources_add))
        }
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
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
                    Text(text = stringResource(R.string.sources_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.sources_cancel))
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
            failure = form.failure,
            onFieldChange = onFormFieldChange,
            onDismiss = onDismissForm,
            onConfirm = onSaveForm,
        )
    }
}

@Composable
private fun SourceRow(
    source: ConnectionSource,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = source.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sources_menu))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                    text = { Text(text = stringResource(R.string.sources_delete)) },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 表单字段顺序固定为名称、服务器地址、路径或起始目录、用户名、密码。 */
@Composable
private fun networkSourceFields(form: NetworkSourceForm): List<NetworkSourceField> {
    val name = NetworkSourceField(stringResource(R.string.network_source_name), form.name)
    val address = NetworkSourceField(stringResource(R.string.network_source_server_address), form.address, required = true)
    val username = NetworkSourceField(stringResource(R.string.network_source_username), form.username)
    val password = NetworkSourceField(stringResource(R.string.network_source_password), form.password, secret = true)
    return when (form.protocol) {
        SourceProtocol.Smb -> listOf(
            name,
            address,
            NetworkSourceField(stringResource(R.string.network_source_share_path), form.path),
            username,
            password,
        )

        SourceProtocol.WebDav -> listOf(
            name,
            address,
            NetworkSourceField(stringResource(R.string.network_source_start_directory), form.path),
            username,
            password,
        )

        SourceProtocol.Local -> emptyList()
    }
}

private fun emptyMessage(protocol: SourceProtocol): Int = when (protocol) {
    SourceProtocol.Local -> R.string.sources_empty_local
    SourceProtocol.Smb -> R.string.sources_empty_smb
    SourceProtocol.WebDav -> R.string.sources_empty_webdav
}
