package com.cc3301.ccplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.ui.theme.AppColors
import com.cc3301.ccplayer.ui.theme.AppRadius

/** 网络源表单的字段；顺序由展示方决定，状态更新按字段本身分派。 */
enum class NetworkSourceFieldKind { Name, Address, Path, Username, Password }

/** 网络源表单里的一个输入框。 */
data class NetworkSourceField(
    val kind: NetworkSourceFieldKind,
    val label: String,
    val value: String,
    val secret: Boolean = false,
    val required: Boolean = false,
)

/**
 * 新增或编辑网络源的表单：字段与取值由调用方给出，提交后由调用方把表单置空表示成功。
 */
@Composable
fun NetworkSourceDialog(
    title: String,
    fields: List<NetworkSourceField>,
    saving: Boolean,
    addressInvalid: Boolean,
    duplicateAddress: Boolean,
    failure: MediaFailure?,
    onFieldChange: (NetworkSourceFieldKind, String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val filled = fields.filter { it.required }.all { it.value.isNotBlank() }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = AppColors.Surface,
        titleContentColor = AppColors.Foreground,
        textContentColor = AppColors.Muted,
        shape = RoundedCornerShape(AppRadius.Sheet),
        title = { Text(text = title) },
        text = {
            Column {
                fields.forEach { field ->
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = { onFieldChange(field.kind, it) },
                        label = { Text(text = field.label) },
                        singleLine = true,
                        enabled = !saving,
                        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.Accent,
                            unfocusedBorderColor = AppColors.Line,
                            focusedLabelColor = AppColors.Accent,
                            unfocusedLabelColor = AppColors.Muted,
                            focusedTextColor = AppColors.Foreground,
                            unfocusedTextColor = AppColors.Foreground,
                            cursorColor = AppColors.Accent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                when {
                    addressInvalid -> ErrorText(text = stringResource(R.string.network_source_address_invalid))
                    duplicateAddress -> ErrorText(text = stringResource(R.string.network_source_address_duplicate))
                    failure != null -> ErrorText(text = stringResource(mediaFailureText(failure)))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = filled && !saving, onClick = onConfirm) {
                Text(text = stringResource(R.string.network_source_confirm), color = AppColors.Accent)
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(text = stringResource(R.string.network_source_cancel), color = AppColors.Muted)
            }
        },
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
