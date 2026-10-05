package com.cc3301.ccplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackState

@Composable
fun PlayerScreen(
    state: PlaybackState,
    progress: PlaybackProgress,
    seekPreviewMillis: Long?,
    playlist: List<MediaFile>,
    currentIndex: Int,
    decodeMode: DecodeMode,
    loopMode: LoopMode,
    autoPlayNext: Boolean,
    surfaceCallback: MpvSurfaceView.Callback,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onPlaylistSelect: (Int) -> Unit,
    onToggleDecodeMode: () -> Unit,
    onToggleLoopMode: () -> Unit,
    onToggleAutoPlayNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var playlistVisible by remember { mutableStateOf(false) }

    Box(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)) {
        AndroidView(
            factory = { context ->
                MpvSurfaceView(context).also { view -> view.callback = surfaceCallback }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { controlsVisible = !controlsVisible },
        )
        if (controlsVisible) {
            Controls(
                state = state,
                progress = progress,
                seekPreviewMillis = seekPreviewMillis,
                playlist = playlist,
                currentIndex = currentIndex,
                decodeMode = decodeMode,
                loopMode = loopMode,
                autoPlayNext = autoPlayNext,
                locked = locked,
                playlistVisible = playlistVisible,
                onTogglePlayPause = onTogglePlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekPreview = onSeekPreview,
                onSeekCommit = onSeekCommit,
                onPlaylistSelect = onPlaylistSelect,
                onToggleDecodeMode = onToggleDecodeMode,
                onToggleLoopMode = onToggleLoopMode,
                onToggleAutoPlayNext = onToggleAutoPlayNext,
                onTogglePlaylist = { playlistVisible = !playlistVisible },
                onToggleLock = { locked = !locked },
                onClose = onClose,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun Controls(
    state: PlaybackState,
    progress: PlaybackProgress,
    seekPreviewMillis: Long?,
    playlist: List<MediaFile>,
    currentIndex: Int,
    decodeMode: DecodeMode,
    loopMode: LoopMode,
    autoPlayNext: Boolean,
    locked: Boolean,
    playlistVisible: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onPlaylistSelect: (Int) -> Unit,
    onToggleDecodeMode: () -> Unit,
    onToggleLoopMode: () -> Unit,
    onToggleAutoPlayNext: () -> Unit,
    onTogglePlaylist: () -> Unit,
    onToggleLock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (playlistVisible && !locked) {
            PlaylistPanel(
                playlist = playlist,
                currentIndex = currentIndex,
                autoPlayNext = autoPlayNext,
                onToggleAutoPlayNext = onToggleAutoPlayNext,
                onSelect = onPlaylistSelect,
            )
        }
        SeekBar(
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            previewMillis = seekPreviewMillis,
            onPreview = onSeekPreview,
            onCommit = onSeekCommit,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevious, enabled = currentIndex > 0) {
                Text(text = stringResource(R.string.player_previous))
            }
            Button(onClick = onTogglePlayPause) {
                Text(
                    text = stringResource(
                        if (state == PlaybackState.Playing) R.string.player_pause else R.string.player_resume,
                    ),
                )
            }
            TextButton(onClick = onNext, enabled = currentIndex in 0 until playlist.lastIndex) {
                Text(text = stringResource(R.string.player_next))
            }
            if (!locked) {
                TextButton(onClick = onToggleLoopMode) {
                    Text(text = stringResource(loopModeLabel(loopMode)))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onTogglePlaylist) {
                Text(text = stringResource(R.string.player_playlist))
            }
            TextButton(onClick = onToggleDecodeMode) {
                Text(text = stringResource(decodeModeLabel(decodeMode)))
            }
            TextButton(onClick = onToggleLock) {
                Text(text = stringResource(if (locked) R.string.player_unlock else R.string.player_lock))
            }
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.player_exit))
            }
        }
    }
}

/** 进度条：拖动中只在气泡里显示预览时间，松手才把位置交给控制层。 */
@Composable
private fun SeekBar(
    positionMillis: Long,
    durationMillis: Long,
    previewMillis: Long?,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
) {
    val maximum = durationMillis.coerceAtLeast(1L)
    val shown = (previewMillis ?: positionMillis).coerceIn(0L, maximum)
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (previewMillis != null) {
                Text(
                    text = formatDuration(previewMillis),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { onPreview(it.toLong()) },
            onValueChangeFinished = onCommit,
            valueRange = 0f..maximum.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PlaylistPanel(
    playlist: List<MediaFile>,
    currentIndex: Int,
    autoPlayNext: Boolean,
    onToggleAutoPlayNext: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_auto_play_next),
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = autoPlayNext, onCheckedChange = { onToggleAutoPlayNext() })
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(playlist) { index, media ->
                Text(
                    text = media.name,
                    color = if (index == currentIndex) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private fun decodeModeLabel(mode: DecodeMode): Int = when (mode) {
    DecodeMode.Hardware -> R.string.player_decode_hardware
    DecodeMode.Software -> R.string.player_decode_software
}

private fun loopModeLabel(mode: LoopMode): Int = when (mode) {
    LoopMode.Sequential -> R.string.player_loop_sequential
    LoopMode.ListLoop -> R.string.player_loop_list
    LoopMode.SingleLoop -> R.string.player_loop_single
}
