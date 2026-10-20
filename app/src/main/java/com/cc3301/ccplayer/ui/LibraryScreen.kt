package com.cc3301.ccplayer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.cc3301.ccplayer.library.relativeDirectoryPath
import com.cc3301.ccplayer.settings.ViewMode
import com.cc3301.ccplayer.ui.theme.AppColors
import com.cc3301.ccplayer.ui.theme.AppRadius
import com.cc3301.ccplayer.ui.theme.AppSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 库页面：按「当前目录即列表」浏览单个连接源，点条目进入子目录或起播。
 *
 * 顶栏为汉堡按钮、当前目录名、视图按钮与排序按钮，不提供返回箭头。
 * 离开目录或滚动停下时记录第一个可见条目与偏移，下次进入据此恢复。
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    mediaPreview: MediaPreviewSource,
    onOpenEntry: (MediaFile) -> Unit,
    onOpenSettings: () -> Unit,
    onSelectView: (ViewChoice) -> Unit,
    onSelectSort: (SortOrder) -> Unit,
    onScrollToTop: () -> Unit,
    onRetry: () -> Unit,
    onSavePosition: (sourceLocation: String, relativePath: String, entryLocation: String, scrollOffset: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val latestState by rememberUpdatedState(state)
    val latestSavePosition by rememberUpdatedState(onSavePosition)
    val sourceLocation = state.sourceLocation
    val relativePath = relativeDirectoryPath(state.stack)

    // 目录读完、排序、视图或位置记忆开关变化后，按状态给出的目标定位一次。
    LaunchedEffect(state.scrollRevision) {
        val target = state.scrollTarget
        val index = target?.index ?: 0
        val offset = target?.offset ?: 0
        if (state.viewMode == ViewMode.List) {
            listState.scrollToItem(index, offset)
        } else {
            gridState.scrollToItem(index, offset)
        }
    }

    // 滚动停下后记录当前位置，避免拖拽期间频繁写盘。
    LaunchedEffect(state.currentDirectory?.location, state.viewMode) {
        val visible = snapshotFlow {
            if (state.viewMode == ViewMode.List) {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            } else {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            }
        }
        visible.collectLatest { (index, offset) ->
            delay(300)
            latestState.visibleEntries.getOrNull(index)?.let { entry ->
                latestSavePosition(sourceLocation, relativePath, entry.location, offset)
            }
        }
    }

    // 离开目录或离开库页面时补记一次，覆盖刚滚动就离开的情况。
    DisposableEffect(state.currentDirectory?.location, state.viewMode) {
        onDispose {
            val current = latestState
            val index = if (current.viewMode == ViewMode.List) {
                listState.firstVisibleItemIndex
            } else {
                gridState.firstVisibleItemIndex
            }
            val offset = if (current.viewMode == ViewMode.List) {
                listState.firstVisibleItemScrollOffset
            } else {
                gridState.firstVisibleItemScrollOffset
            }
            current.visibleEntries.getOrNull(index)?.let { entry ->
                latestSavePosition(sourceLocation, relativePath, entry.location, offset)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        ScreenHeader(
            title = state.currentDirectory?.name.orEmpty(),
            leading = {
                HeaderIconButton(
                    icon = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.settings_open),
                    onClick = onOpenSettings,
                )
            },
            trailing = {
                LibraryHeaderActions(
                    state = state,
                    onSelectView = onSelectView,
                    onSelectSort = onSelectSort,
                    onScrollToTop = onScrollToTop,
                )
            },
            titlePadding = 104.dp,
        )
        when {
            state.loading -> Centered { CircularProgressIndicator(color = AppColors.Accent) }
            state.failure != null -> Centered {
                Message(text = stringResource(mediaFailureText(state.failure)), onRetry = onRetry)
            }
            state.visibleEntries.isEmpty() -> EmptyState(
                painter = painterResource(R.drawable.ic_media_folder),
                tint = AppColors.Accent,
                background = AppColors.SurfaceHigh,
                title = stringResource(R.string.library_empty_title),
                message = stringResource(R.string.library_empty),
                modifier = Modifier.fillMaxSize(),
            )
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

/** 库页面顶栏右侧：视图与排序按钮，当前项在菜单里带勾选。 */
@Composable
private fun LibraryHeaderActions(
    state: LibraryState,
    onSelectView: (ViewChoice) -> Unit,
    onSelectSort: (SortOrder) -> Unit,
    onScrollToTop: () -> Unit,
) {
    Row {
        ViewMenuButton(
            current = viewChoiceOf(state.viewMode, state.gridColumns),
            onSelect = onSelectView,
        )
        SortMenuButton(
            current = state.sortOrder,
            onSelect = onSelectSort,
            onScrollToTop = onScrollToTop,
        )
    }
}

@Composable
private fun ViewMenuButton(current: ViewChoice, onSelect: (ViewChoice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_view_mode),
                contentDescription = stringResource(R.string.library_view),
                tint = AppColors.Foreground,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AppColors.SurfaceHigh,
            shape = RoundedCornerShape(AppRadius.Md),
        ) {
            viewChoices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(text = optionText(viewChoiceLabel(choice))) },
                    onClick = {
                        expanded = false
                        onSelect(choice)
                    },
                    trailingIcon = {
                        if (choice == current) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = AppColors.Accent)
                        }
                    },
                )
            }
        }
    }
}

/** 单击打开排序菜单；长按回到顶部，且不改变排序。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SortMenuButton(
    current: SortOrder,
    onSelect: (SortOrder) -> Unit,
    onScrollToTop: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { expanded = true },
                    onLongClick = onScrollToTop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                contentDescription = stringResource(R.string.library_sort),
                tint = AppColors.Foreground,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AppColors.SurfaceHigh,
            shape = RoundedCornerShape(AppRadius.Md),
        ) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(sortLabel(order))) },
                    onClick = {
                        expanded = false
                        onSelect(order)
                    },
                    trailingIcon = {
                        if (order == current) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = AppColors.Accent)
                        }
                    },
                )
            }
        }
    }
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
        ViewMode.List -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppSpacing.Lg,
                end = AppSpacing.Lg,
                top = 6.dp,
                bottom = 28.dp,
            ),
        ) {
            listItems(entries, key = { it.location }) { entry ->
                EntryRow(entry = entry, mediaPreview = mediaPreview, onOpenEntry = onOpenEntry)
            }
        }

        ViewMode.Grid -> LazyVerticalGrid(
            columns = GridCells.Fixed(state.gridColumns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(entries, key = { it.location }) { entry ->
                EntryCard(entry = entry, mediaPreview = mediaPreview, onOpenEntry = onOpenEntry)
            }
        }
    }
}

@Composable
private fun EntryRow(entry: MediaFile, mediaPreview: MediaPreviewSource, onOpenEntry: (MediaFile) -> Unit) {
    val preview by produceState<MediaPreview?>(initialValue = null, entry.location) {
        value = mediaPreview.preview(entry)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.Md))
            .clickable { onOpenEntry(entry) }
            .padding(horizontal = AppSpacing.Sm, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
    ) {
        Thumbnail(entry = entry, preview = preview, width = 76.dp, height = 46.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.isDirectory) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppColors.Faint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 网格卡片：缩略图（或占位图）+ 文件名。 */
@Composable
private fun EntryCard(entry: MediaFile, mediaPreview: MediaPreviewSource, onOpenEntry: (MediaFile) -> Unit) {
    val preview by produceState<MediaPreview?>(initialValue = null, entry.location) {
        value = mediaPreview.preview(entry)
    }
    Column(modifier = Modifier.clickable { onOpenEntry(entry) }) {
        Thumbnail(
            entry = entry,
            preview = preview,
            width = null,
            height = null,
            aspectRatio = cardAspectRatio(entry),
            cornerRadius = 10.dp,
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** 列表与网格共用的缩略图：目录用图标底座，文件有预览图就显示，否则用种类占位图。 */
@Composable
private fun Thumbnail(
    entry: MediaFile,
    preview: MediaPreview?,
    width: androidx.compose.ui.unit.Dp?,
    height: androidx.compose.ui.unit.Dp?,
    aspectRatio: Float? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = 9.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val background = if (entry.isDirectory) AppColors.SurfaceHigh else AppColors.SurfaceHighest
    val base = when {
        width != null && height != null -> Modifier.size(width = width, height = height)
        aspectRatio != null -> Modifier.fillMaxWidth().aspectRatio(aspectRatio)
        else -> Modifier.fillMaxWidth()
    }
    Box(
        modifier = base
            .clip(shape)
            .background(background),
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
                tint = if (entry.isDirectory) AppColors.Accent else AppColors.Muted,
                modifier = Modifier.size(if (entry.isDirectory) 24.dp else 20.dp),
            )
        }
        val duration = preview?.durationMillis
        if (duration != null && !entry.isDirectory) {
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AppSpacing.Xs)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(4.dp))
                    .padding(horizontal = AppSpacing.Xs, vertical = 1.dp),
            )
        }
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
private fun Message(text: String, onRetry: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(AppSpacing.Lg))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.AccentDeep,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(AppRadius.Md),
            ) {
                Text(text = stringResource(R.string.library_retry))
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
