package com.cc3301.ccplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.settings.ResumeMode
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.ViewMode
import com.cc3301.ccplayer.ui.theme.AppColors
import com.cc3301.ccplayer.ui.theme.AppRadius
import com.cc3301.ccplayer.ui.theme.AppSpacing

/**
 * 设置页：按播放与列表分组，归总连播、起播位置、解码、屏幕方向、视图、排序与目录位置记忆。
 * 顶栏进入，改动即写入持久设置。
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onSetAutoPlayNext: (Boolean) -> Unit,
    onSetResumeMode: (ResumeMode) -> Unit,
    onSetAutoRestorePosition: (Boolean) -> Unit,
    onSetDecodeMode: (DecodeMode) -> Unit,
    onSetOrientationMode: (OrientationMode) -> Unit,
    onSetViewMode: (ViewMode) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetSortOrder: (SortOrder) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
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
                    optionLabel = { OptionLabel(decodeModeLabel(it)) },
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
                ChoiceSetting(
                    label = stringResource(R.string.settings_view_title),
                    options = viewChoices,
                    selected = viewChoiceOf(state),
                    optionLabel = ::viewChoiceLabel,
                    onSelect = { choice ->
                        if (choice.viewMode == ViewMode.Grid) onSetGridColumns(choice.gridColumns)
                        onSetViewMode(choice.viewMode)
                    },
                )
                RowDivider()
                ChoiceSetting(
                    label = stringResource(R.string.settings_sort_title),
                    options = SortOrder.entries,
                    selected = state.sortOrder,
                    optionLabel = { OptionLabel(sortLabel(it)) },
                    onSelect = onSetSortOrder,
                )
                RowDivider()
                ToggleSetting(
                    label = stringResource(R.string.settings_auto_restore_position),
                    checked = state.autoRestorePosition,
                    onCheckedChange = onSetAutoRestorePosition,
                )
            }
        }
    }
}

internal val viewChoices: List<ViewChoice> = buildList {
    add(ViewChoice(ViewMode.List, SettingsRepository.DEFAULT_GRID_COLUMNS))
    SettingsRepository.GRID_COLUMN_CHOICES.forEach { columns -> add(ViewChoice(ViewMode.Grid, columns)) }
}

/** 当前视图：单列列表固定对应列表选项，网格按列数对应各自选项。 */
private fun viewChoiceOf(state: SettingsState): ViewChoice = when (state.viewMode) {
    ViewMode.List -> ViewChoice(ViewMode.List, SettingsRepository.DEFAULT_GRID_COLUMNS)
    ViewMode.Grid -> ViewChoice(ViewMode.Grid, state.gridColumns)
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
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Foreground,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppColors.AccentDeep,
                uncheckedThumbColor = Color(0xFFC9D3E2),
                uncheckedTrackColor = AppColors.SurfaceHighest,
                uncheckedBorderColor = Color.Transparent,
            ),
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
                .clickable { expanded = true }
                .padding(horizontal = 15.dp, vertical = 14.dp),
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

@Composable
private fun optionText(label: OptionLabel): String {
    val argument = label.formatArg
    return if (argument == null) {
        stringResource(label.text)
    } else {
        stringResource(label.text, argument)
    }
}
