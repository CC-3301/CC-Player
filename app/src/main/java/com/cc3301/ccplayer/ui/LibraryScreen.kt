package com.cc3301.ccplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaKind
import com.cc3301.ccplayer.library.mediaKindOf

/**
 * 媒体列表：首次启动引导选目录，之后按「当前目录即列表」浏览，点条目进入子目录或起播。
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    onPickRoot: () -> Unit,
    onOpenEntry: (MediaFile) -> Unit,
    onNavigateUp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        if (state.ready && (state.stack.isNotEmpty() || state.roots.isNotEmpty())) {
            Header(state = state, onNavigateUp = onNavigateUp, onPickRoot = onPickRoot)
        }
        when {
            !state.ready -> Centered { CircularProgressIndicator() }
            state.roots.isEmpty() -> Onboarding(onPickRoot = onPickRoot)
            state.loading -> Centered { CircularProgressIndicator() }
            state.failed -> Centered {
                Message(text = stringResource(R.string.library_load_failed), onRetry = onRetry)
            }
            state.entries.isEmpty() -> Centered { Message(text = stringResource(R.string.library_empty)) }
            else -> EntryList(entries = state.entries, onOpenEntry = onOpenEntry)
        }
    }
}

@Composable
private fun Header(state: LibraryState, onNavigateUp: () -> Unit, onPickRoot: () -> Unit) {
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
        if (state.stack.isEmpty()) {
            TextButton(onClick = onPickRoot) {
                Text(text = stringResource(R.string.library_add_root))
            }
        }
    }
}

@Composable
private fun EntryList(entries: List<MediaFile>, onOpenEntry: (MediaFile) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.location }) { entry ->
            EntryRow(entry = entry, onOpenEntry = onOpenEntry)
            HorizontalDivider()
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
