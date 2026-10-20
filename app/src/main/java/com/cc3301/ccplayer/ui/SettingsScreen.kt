package com.cc3301.ccplayer.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.settings.ResumeMode
import com.cc3301.ccplayer.ui.theme.AppColors
import com.cc3301.ccplayer.ui.theme.AppRadius
import com.cc3301.ccplayer.ui.theme.AppSpacing

/**
 * 设置页：按播放与列表分组，归总连播、后台播放、起播位置、解码、屏幕方向与目录位置记忆。
 * 顶栏进入，改动即写入持久设置。视图与排序由库页面顶栏提供，不在设置页。
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onSetAutoPlayNext: (Boolean) -> Unit,
    onSetBackgroundPlayback: (Boolean) -> Unit,
    onSetResumeMode: (ResumeMode) -> Unit,
    onSetAutoRestorePosition: (Boolean) -> Unit,
    onSetDecodeMode: (DecodeMode) -> Unit,
    onSetOrientationMode: (OrientationMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        ScreenHeader(
            title = stringResource(R.string.settings_open),
            leading = {
                HeaderIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    onClick = onClose,
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = AppSpacing.Lg, end = AppSpacing.Lg, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Xxl),
        ) {
            SettingsGroup(title = stringResource(R.string.settings_playback_section)) {
                ToggleSetting(
                    label = stringResource(R.string.settings_auto_play_next),
                    checked = state.autoPlayNext,
                    onCheckedChange = onSetAutoPlayNext,
                )
                RowDivider()
                ToggleSetting(
                    label = stringResource(R.string.settings_background_playback),
                    checked = state.backgroundPlayback,
                    onCheckedChange = onSetBackgroundPlayback,
                )
                RowDivider()
                ChoiceSetting(
                    label = stringResource(R.string.settings_resume_title),
                    options = ResumeMode.entries,
                    selected = state.resumeMode,
                    optionLabel = { OptionLabel(resumeModeLabel(it)) },
                    onSelect = onSetResumeMode,
                )
                RowDivider()
                ChoiceSetting(
                    label = stringResource(R.string.settings_decode_title),
                    options = DecodeMode.entries,
                    selected = state.decodeMode,
                    optionLabel = { OptionLabel(settingsDecodeModeLabel(it)) },
                    onSelect = onSetDecodeMode,
                )
                RowDivider()
                ChoiceSetting(
                    label = stringResource(R.string.settings_orientation_title),
                    options = OrientationMode.entries,
                    selected = state.orientationMode,
                    optionLabel = { OptionLabel(orientationModeLabel(it)) },
                    onSelect = onSetOrientationMode,
                )
            }
            SettingsGroup(title = stringResource(R.string.settings_list_section)) {
                ToggleSetting(
                    label = stringResource(R.string.settings_auto_restore_position),
                    checked = state.autoRestorePosition,
                    onCheckedChange = onSetAutoRestorePosition,
                )
            }
        }
    }
}

private fun resumeModeLabel(mode: ResumeMode): Int = when (mode) {
    ResumeMode.LastPosition -> R.string.settings_resume_last_position
    ResumeMode.FromStart -> R.string.settings_resume_from_start
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = AppColors.Line, thickness = 1.dp)
}

/** 开关设置项：整行可点。 */
@Composable
private fun ToggleSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Foreground,
            modifier = Modifier.weight(1f),
        )
        SettingSwitch(checked = checked)
    }
}

/** 设置开关：固定 44×26，右侧与选项行的箭头共用同一条基准线。 */
@Composable
private fun SettingSwitch(checked: Boolean) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        label = "settings-switch-thumb",
    )
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) AppColors.AccentDeep else AppColors.SurfaceHighest),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else AppColors.SwitchThumb),
        )
    }
}

/** 多选一设置项：当前取值显示在行尾，点开菜单换值。 */
@Composable
private fun <T> ChoiceSetting(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> OptionLabel,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.Foreground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = optionText(optionLabel(selected)),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppColors.Faint,
                modifier = Modifier.padding(start = AppSpacing.Xs),
            )
        }
        // 菜单以行尾为锚点向左展开，靠近右侧触发位置。
        Box(modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight()) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = AppColors.SurfaceHigh,
                shape = RoundedCornerShape(AppRadius.Md),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text = optionText(optionLabel(option))) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        trailingIcon = {
                            if (option == selected) {
                                Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = AppColors.Accent)
                            }
                        },
                    )
                }
            }
        }
    }
}
