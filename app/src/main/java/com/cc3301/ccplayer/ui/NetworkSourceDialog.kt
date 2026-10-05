package com.cc3301.ccplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.R

/** 网络源表单里的一个输入框。 */
data class NetworkSourceField(val label: String, val secret: Boolean = false)

/**
 * 新增网络源表单：字段名与个数由调用方给出，协议相关的部分留在调用方。
 *
 * 提交后表单不自行关闭：调用方把 [state] 置空表示成功。
 */
@Composable
fun NetworkSourceDialog(
    title: String,
    fields: List<NetworkSourceField>,
    state: NetworkSourceForm,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember(fields) { mutableStateListOf<String>().apply { repeat(fields.size) { add("") } } }
    val filled = values.all { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = { if (!state.adding) onDismiss() },
        title = { Text(text = title) },
        text = {
            Column {
                fields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { values[index] = it },
                        label = { Text(text = field.label) },
                        singleLine = true,
                        enabled = !state.adding,
                        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                state.failure?.let { failure ->
                    Text(
                        text = stringResource(mediaFailureText(failure)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = filled && !state.adding, onClick = { onConfirm(values.toList()) }) {
                Text(text = stringResource(R.string.network_source_confirm))
            }
        },
        dismissButton = {
            TextButton(enabled = !state.adding, onClick = onDismiss) {
                Text(text = stringResource(R.string.network_source_cancel))
            }
        },
    )
}
