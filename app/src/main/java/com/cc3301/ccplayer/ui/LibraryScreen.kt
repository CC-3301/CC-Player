package com.cc3301.ccplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaKind
import com.cc3301.ccplayer.library.MediaPreview
import com.cc3301.ccplayer.library.MediaPreviewSource
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.library.mediaKindOf
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.ViewMode

/**
 * 媒体列表：首次启动引导选目录，之后按「当前目录即列表」浏览，点条目进入子目录或起播。
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    mediaPreview: MediaPreviewSource,
    onPickRoot: () -> Unit,
    onOpenEntry: (MediaFile) -> Unit,
    onNavigateUp: () -> Unit,
    onRetry: () -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onGridColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // 换排序后回到列表最顶部。
    LaunchedEffect(state.sortRevision) {
        if (state.viewMode == ViewMode.List) listState.scrollToItem(0) else gridState.scrollToItem(0)
    }

    // 进入目录后滚动到上次播放的条目。
    LaunchedEffect(state.scrollRevision) {
        val target = state.scrollTargetLocation ?: return@LaunchedEffect
        val index = state.visibleEntries.indexOfFirst { it.location == target }
        if (index < 0) return@LaunchedEffect
        if (state.viewMode == ViewMode.List) listState.scrollToItem(index) else gridState.scrollToItem(index)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        if (state.ready && (state.stack.isNotEmpty() || state.roots.isNotEmpty())) {
            Header(
                state = state,
                onNavigateUp = onNavigateUp,
                onPickRoot = onPickRoot,
                onSortOrderChange = onSortOrderChange,
                onViewModeChange = onViewModeChange,
                onGridColumnsChange = onGridColumnsChange,
            )
        }
        when {
            !state.ready -> Centered { CircularProgressIndicator() }
            state.roots.isEmpty() -> Onboarding(onPickRoot = onPickRoot)
            state.loading -> Centered { CircularProgressIndicator() }
            state.failed -> Centered {
                Message(text = stringResource(R.string.library_load_failed), onRetry = onRetry)
            }
            state.visibleEntries.isEmpty() -> Centered { Message(text = stringResource(R.string.library_empty)) }
            else -> EntryArea(
                state = state,
                mediaPreview = mediaPreview,
                listState = listState,
                gridState = gridState,
                onOpenEntry = onOpenEntry,
            )
        }
    }
}

@Composable
private fun Header(
    state: LibraryState,
    onNavigateUp: () -> Unit,
    onPickRoot: () -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onGridColumnsChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.stack.isNotEmpty()) {
            TextButton(onClick = onNavigateUp) {
                Text(text = stringResource(R.string.library_up))
            }
        }
        Text(
            text = state.currentDirectory?.name ?: stringResource(R.string.library_roots_title),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        SortMenu(sortOrder = state.sortOrder, onSortOrderChange = onSortOrderChange)
        ViewMenu(
            viewMode = state.viewMode,
            gridColumns = state.gridColumns,
            onViewModeChange = onViewModeChange,
            onGridColumnsChange = onGridColumnsChange,
        )
        if (state.stack.isEmpty()) {
            TextButton(onClick = onPickRoot) {
                Text(text = stringResource(R.string.library_add_root))
            }
        }
    }
}

@Composable
private fun SortMenu(sortOrder: SortOrder, onSortOrderChange: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(text = stringResource(R.string.library_sort_title))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(sortLabel(option))) },
                    onClick = {
                        expanded = false
                        onSortOrderChange(option)
                    },
                    trailingIcon = { SelectedMark(selected = option == sortOrder) },
                )
            }
        }
    }
}

@Composable
private fun ViewMenu(
    viewMode: ViewMode,
    gridColumns: Int,
    onViewModeChange: (ViewMode) -> Unit,
    onGridColumnsChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(text = stringResource(R.string.library_view_title))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.library_view_list)) },
                onClick = {
                    expanded = false
                    onViewModeChange(ViewMode.List)
                },
                trailingIcon = { SelectedMark(selected = viewMode == ViewMode.List) },
            )
            SettingsRepository.GRID_COLUMN_CHOICES.forEach { columns ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.library_view_grid_columns, columns)) },
                    onClick = {
                        expanded = false
                        onGridColumnsChange(columns)
                        onViewModeChange(ViewMode.Grid)
                    },
                    trailingIcon = {
                        SelectedMark(selected = viewMode == ViewMode.Grid && columns == gridColumns)
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectedMark(selected: Boolean) {
    if (selected) Icon(imageVector = Icons.Filled.Check, contentDescription = null)
}

@Composable
private fun EntryArea(
    state: LibraryState,
    mediaPreview: MediaPreviewSource,
    listState: LazyListState,
    gridState: LazyGridState,
    onOpenEntry: (MediaFile) -> Unit,
) {
    val entries = state.visibleEntries
    when (state.viewMode) {
        ViewMode.List -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            listItems(entries, key = { it.location }) { entry ->
                EntryRow(entry = entry, onOpenEntry = onOpenEntry)
                HorizontalDivider()
            }
        }

        ViewMode.Grid -> LazyVerticalGrid(
            columns = GridCells.Fixed(state.gridColumns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            gridItems(entries, key = { it.location }) { entry ->
                EntryCard(entry = entry, mediaPreview = mediaPreview, onOpenEntry = onOpenEntry)
            }
        }
    }
}

@Composable
private fun EntryRow(entry: MediaFile, onOpenEntry: (MediaFile) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenEntry(entry) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = stringResource(entryKindLabel(entry)), style = MaterialTheme.typography.labelMedium)
    }
}

/** 网格卡片：缩略图（或占位图）+ 文件名 + 时长角标。 */
@Composable
private fun EntryCard(entry: MediaFile, mediaPreview: MediaPreviewSource, onOpenEntry: (MediaFile) -> Unit) {
    val preview by produceState<MediaPreview?>(initialValue = null, entry.location) {
        value = mediaPreview.preview(entry)
    }
    Column(modifier = Modifier.clickable { onOpenEntry(entry) }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(cardAspectRatio(entry))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val image = preview?.image
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(placeholderIcon(entry)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
            val duration = preview?.durationMillis
            if (duration != null) {
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** 音频封面是方形，视频与目录用宽屏比例。 */
private fun cardAspectRatio(entry: MediaFile): Float =
    if (mediaKindOf(entry.name) == MediaKind.Audio) 1f else 16f / 9f

/** 取不到预览图时按条目种类选占位图。 */
private fun placeholderIcon(entry: MediaFile): Int = when {
    entry.isDirectory -> R.drawable.ic_media_folder
    mediaKindOf(entry.name) == MediaKind.Audio -> R.drawable.ic_media_audio
    else -> R.drawable.ic_media_video
}

@Composable
private fun Onboarding(onPickRoot: () -> Unit) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.library_onboarding_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onPickRoot) {
                Text(text = stringResource(R.string.library_pick_root))
            }
        }
    }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.library_retry))
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun entryKindLabel(entry: MediaFile): Int = when {
    entry.isDirectory -> R.string.library_entry_directory
    mediaKindOf(entry.name) == MediaKind.Audio -> R.string.library_entry_audio
    else -> R.string.library_entry_video
}

private fun sortLabel(order: SortOrder): Int = when (order) {
    SortOrder.NameAscending -> R.string.library_sort_name_ascending
    SortOrder.NameDescending -> R.string.library_sort_name_descending
    SortOrder.LastModifiedNewestFirst -> R.string.library_sort_time_newest_first
    SortOrder.LastModifiedOldestFirst -> R.string.library_sort_time_oldest_first
}
