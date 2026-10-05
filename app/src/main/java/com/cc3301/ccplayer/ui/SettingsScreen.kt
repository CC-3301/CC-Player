package com.cc3301.ccplayer.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.settings.ResumeMode
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.ViewMode

/**
 * 设置页：归总连播开关、起播位置、自动滚动、解码模式、屏幕方向与列表视图/排序默认值。
 * 顶栏进入，改动即写入持久设置。
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onSetAutoPlayNext: (Boolean) -> Unit,
    onSetResumeMode: (ResumeMode) -> Unit,
    onSetAutoScrollToLastPlayed: (Boolean) -> Unit,
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
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.settings_back))
            }
            Text(
                text = stringResource(R.string.settings_open),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(text = stringResource(R.string.settings_playback_section))
            ToggleSetting(
                label = stringResource(R.string.settings_auto_play_next),
                checked = state.autoPlayNext,
                onCheckedChange = onSetAutoPlayNext,
            )
            ChoiceSetting(
                label = stringResource(R.string.settings_resume_title),
                options = ResumeMode.entries,
                selected = state.resumeMode,
                optionLabel = ::resumeModeLabel,
                onSelect = onSetResumeMode,
            )
            ToggleSetting(
                label = stringResource(R.string.settings_auto_scroll),
                checked = state.autoScrollToLastPlayed,
                onCheckedChange = onSetAutoScrollToLastPlayed,
            )
            ChoiceSetting(
                label = stringResource(R.string.settings_decode_title),
                options = DecodeMode.entries,
                selected = state.decodeMode,
                optionLabel = ::decodeModeLabel,
                onSelect = onSetDecodeMode,
            )
            ChoiceSetting(
                label = stringResource(R.string.settings_orientation_title),
                options = OrientationMode.entries,
                selected = state.orientationMode,
                optionLabel = ::orientationModeLabel,
                onSelect = onSetOrientationMode,
            )
            SectionTitle(text = stringResource(R.string.settings_list_section))
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
            ChoiceSetting(
                label = stringResource(R.string.settings_sort_title),
                options = SortOrder.entries,
                selected = state.sortOrder,
                optionLabel = ::sortLabel,
                onSelect = onSetSortOrder,
            )
        }
    }
}

/** 视图选项：单列列表与 2/3/4 列网格。 */
private data class ViewChoice(val viewMode: ViewMode, val gridColumns: Int)

private val viewChoices: List<ViewChoice> = buildList {
    add(ViewChoice(ViewMode.List, SettingsRepository.DEFAULT_GRID_COLUMNS))
    SettingsRepository.GRID_COLUMN_CHOICES.forEach { columns -> add(ViewChoice(ViewMode.Grid, columns)) }
}

/** 当前视图：单列列表固定对应列表选项，网格按列数对应各自选项。 */
private fun viewChoiceOf(state: SettingsState): ViewChoice = when (state.viewMode) {
    ViewMode.List -> ViewChoice(ViewMode.List, SettingsRepository.DEFAULT_GRID_COLUMNS)
    ViewMode.Grid -> ViewChoice(ViewMode.Grid, state.gridColumns)
}

private fun viewChoiceLabel(choice: ViewChoice): Int = when (choice.viewMode) {
    ViewMode.List -> R.string.library_view_list
    ViewMode.Grid -> R.string.library_view_grid_columns
}

private fun resumeModeLabel(mode: ResumeMode): Int = when (mode) {
    ResumeMode.LastPosition -> R.string.settings_resume_last_position
    ResumeMode.FromStart -> R.string.settings_resume_from_start
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** 开关设置项：整行可点。 */
@Composable
private fun ToggleSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 多选一设置项：当前取值显示在按钮上，点开菜单换值。 */
@Composable
private fun <T> ChoiceSetting(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = stringResource(optionLabel(selected)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text = stringResource(optionLabel(option))) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        trailingIcon = {
                            if (option == selected) Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}
