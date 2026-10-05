package com.cc3301.ccplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.SleepTimerOption
import com.cc3301.ccplayer.playback.SleepTimerState
import com.cc3301.ccplayer.playback.customSleepMinutes
import com.cc3301.ccplayer.settings.OrientationMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(
    state: PlaybackState,
    progress: PlaybackProgress,
    seekPreviewMillis: Long?,
    playlist: List<MediaFile>,
    currentIndex: Int,
    decodeMode: DecodeMode,
    loopMode: LoopMode,
    orientationMode: OrientationMode,
    autoPlayNext: Boolean,
    sleepTimerState: SleepTimerState,
    levelControls: PlayerLevelControls,
    surfaceCallback: MpvSurfaceView.Callback,
    videoAspectRatio: Double?,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPlaylistSelect: (Int) -> Unit,
    onToggleDecodeMode: () -> Unit,
    onToggleLoopMode: () -> Unit,
    onCycleOrientationMode: () -> Unit,
    onToggleAutoPlayNext: () -> Unit,
    onSelectSleepTimer: (SleepTimerOption) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lock = remember { PlayerLock() }
    var controlsVisible by remember { mutableStateOf(true) }
    var playlistVisible by remember { mutableStateOf(false) }
    var sleepTimerVisible by remember { mutableStateOf(false) }
    var levelHint by remember { mutableStateOf<LevelHint?>(null) }
    val locked = lock.locked

    LaunchedEffect(lock.unlockPending) {
        if (lock.unlockPending) {
            delay(UNLOCK_CONFIRM_WINDOW_MILLIS)
            lock.expireUnlock()
        }
    }

    BoxWithConstraints(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)) {
        val density = LocalDensity.current
        val viewport = videoViewport(
            containerWidth = with(density) { maxWidth.toPx() },
            containerHeight = with(density) { maxHeight.toPx() },
            videoAspectRatio = videoAspectRatio,
        )
        AndroidView(
            factory = { context ->
                MpvSurfaceView(context).also { view -> view.callback = surfaceCallback }
            },
            modifier = Modifier
                .offset { IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt()) }
                .size(
                    width = with(density) { viewport.width.toDp() },
                    height = with(density) { viewport.height.toDp() },
                ),
        )
        PlayerGestureLayer(
            locked = locked,
            levelControls = levelControls,
            onTogglePlayPause = onTogglePlayPause,
            onSeekBy = onSeekBy,
            onToggleControls = { controlsVisible = !controlsVisible },
            onLevelHint = { levelHint = it },
            modifier = Modifier.fillMaxSize(),
        )
        levelHint?.let { hint ->
            LevelHintLabel(hint = hint, modifier = Modifier.align(Alignment.Center))
        }
        if (inputAllowed(PlayerInput.ControlBar, locked)) {
            if (controlsVisible) {
                PlayerTitleBar(
                    title = playlist.getOrNull(currentIndex)?.name.orEmpty(),
                    onBack = onClose,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                Controls(
                    state = state,
                    progress = progress,
                    seekPreviewMillis = seekPreviewMillis,
                    playlist = playlist,
                    currentIndex = currentIndex,
                    decodeMode = decodeMode,
                    loopMode = loopMode,
                    orientationMode = orientationMode,
                    autoPlayNext = autoPlayNext,
                    sleepTimerState = sleepTimerState,
                    playlistVisible = playlistVisible,
                    sleepTimerVisible = sleepTimerVisible,
                    onTogglePlayPause = onTogglePlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeekPreview = onSeekPreview,
                    onSeekCommit = onSeekCommit,
                    onPlaylistSelect = onPlaylistSelect,
                    onToggleDecodeMode = onToggleDecodeMode,
                    onToggleLoopMode = onToggleLoopMode,
                    onCycleOrientationMode = onCycleOrientationMode,
                    onToggleAutoPlayNext = onToggleAutoPlayNext,
                    onSelectSleepTimer = { option ->
                        onSelectSleepTimer(option)
                        sleepTimerVisible = false
                    },
                    onCancelSleepTimer = {
                        onCancelSleepTimer()
                        sleepTimerVisible = false
                    },
                    onTogglePlaylist = { playlistVisible = !playlistVisible },
                    onToggleSleepTimer = { sleepTimerVisible = !sleepTimerVisible },
                    onLock = { lock.tapLockButton() },
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        } else {
            UnlockButton(
                unlockPending = lock.unlockPending,
                onTap = {
                    lock.tapLockButton()
                    if (!lock.locked) controlsVisible = true
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/** 手势层：点击显隐控制条、双击三分区快退/暂停/快进、左右半屏上下拖调亮度/音量。 */
@Composable
private fun PlayerGestureLayer(
    locked: Boolean,
    levelControls: PlayerLevelControls,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onToggleControls: () -> Unit,
    onLevelHint: (LevelHint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 手势层只在锁定态变化时重启，回调取最新一份，避免双击用上旧的播放位置。
    val latestOnTogglePlayPause = rememberUpdatedState(onTogglePlayPause)
    val latestOnSeekBy = rememberUpdatedState(onSeekBy)

    Box(
        modifier = modifier
            .pointerInput(locked) {
                if (!inputAllowed(PlayerInput.Tap, locked)) return@pointerInput
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        if (inputAllowed(PlayerInput.DoubleTap, locked)) {
                            when (val action = doubleTapAction(offset.x, size.width)) {
                                is DoubleTapAction.Seek -> latestOnSeekBy.value(action.deltaMillis)
                                DoubleTapAction.TogglePlayPause -> latestOnTogglePlayPause.value()
                            }
                        }
                    },
                )
            }
            .pointerInput(locked) {
                if (!inputAllowed(PlayerInput.LevelDrag, locked)) return@pointerInput
                var dragStartX = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset -> dragStartX = offset.x },
                    onDragEnd = { onLevelHint(null) },
                    onDragCancel = { onLevelHint(null) },
                    onVerticalDrag = { _, dragAmount ->
                        val target = dragTarget(dragStartX, size.width)
                        val fraction = when (target) {
                            DragTarget.Brightness -> levelControls.dragBrightness(dragAmount, size.height)
                            DragTarget.Volume -> levelControls.dragVolume(dragAmount, size.height)
                        }
                        onLevelHint(LevelHint(target, fraction))
                    },
                )
            },
    )
}

private data class LevelHint(val target: DragTarget, val fraction: Float)

/** 拖动亮度/音量时的百分比提示。 */
@Composable
private fun LevelHintLabel(hint: LevelHint, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            when (hint.target) {
                DragTarget.Brightness -> R.string.player_brightness_hint
                DragTarget.Volume -> R.string.player_volume_hint
            },
            (hint.fraction * 100).roundToInt(),
        ),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * 叠加在画面之上的标题栏：返回按钮 + 当前文件名。透明底，不占布局高度，文字靠阴影在任意画面上可读。
 *
 * 整条区域吞掉点按与拖动，标题栏上的手势不落到下面的手势层上；返回按钮吃掉自己的点击。
 */
@Composable
private fun PlayerTitleBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .safeDrawingPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(R.string.player_title_back), style = playerOverlayTextStyle())
        }
        Text(
            text = title,
            style = playerOverlayTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 透明底上的白字：阴影保证画面亮时也看得清。 */
@Composable
private fun playerOverlayTextStyle(): TextStyle = MaterialTheme.typography.titleMedium.copy(
    color = Color.White,
    shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 4f),
)

/** 锁定态下唯一可见的控件：点两次解锁，第一次点后给出「再点一次解锁」提示。 */
@Composable
private fun UnlockButton(unlockPending: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onTap,
        modifier = modifier
            .safeDrawingPadding()
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
    ) {
        Text(
            text = stringResource(
                if (unlockPending) R.string.player_unlock_confirm else R.string.player_unlock,
            ),
        )
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
    orientationMode: OrientationMode,
    autoPlayNext: Boolean,
    sleepTimerState: SleepTimerState,
    playlistVisible: Boolean,
    sleepTimerVisible: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onPlaylistSelect: (Int) -> Unit,
    onToggleDecodeMode: () -> Unit,
    onToggleLoopMode: () -> Unit,
    onCycleOrientationMode: () -> Unit,
    onToggleAutoPlayNext: () -> Unit,
    onSelectSleepTimer: (SleepTimerOption) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onTogglePlaylist: () -> Unit,
    onToggleSleepTimer: () -> Unit,
    onLock: () -> Unit,
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
        if (playlistVisible) {
            PlaylistPanel(
                playlist = playlist,
                currentIndex = currentIndex,
                autoPlayNext = autoPlayNext,
                onToggleAutoPlayNext = onToggleAutoPlayNext,
                onSelect = onPlaylistSelect,
            )
        }
        if (sleepTimerVisible) {
            SleepTimerPanel(
                sleepTimerState = sleepTimerState,
                onSelect = onSelectSleepTimer,
                onCancel = onCancelSleepTimer,
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
            TextButton(onClick = onToggleLoopMode) {
                Text(text = stringResource(loopModeLabel(loopMode)))
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
            TextButton(onClick = onToggleSleepTimer) {
                Text(
                    text = stringResource(
                        if (sleepTimerState == SleepTimerState.Off) {
                            R.string.player_sleep_timer
                        } else {
                            R.string.player_sleep_timer_armed
                        },
                    ),
                )
            }
            TextButton(onClick = onToggleDecodeMode) {
                Text(text = stringResource(decodeModeLabel(decodeMode)))
            }
            TextButton(onClick = onCycleOrientationMode) {
                Text(text = stringResource(orientationModeLabel(orientationMode)))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onLock) {
                Text(text = stringResource(R.string.player_lock))
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

/** 定时暂停面板：四段固定时长、播完本集、自定义分钟数，以及取消。 */
@Composable
private fun SleepTimerPanel(
    sleepTimerState: SleepTimerState,
    onSelect: (SleepTimerOption) -> Unit,
    onCancel: () -> Unit,
) {
    var customMinutes by remember { mutableStateOf("") }
    var customRejected by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        fixedSleepTimerChoices.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { (label, option) ->
                    TextButton(onClick = { onSelect(option) }) {
                        Text(text = stringResource(label))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = customMinutes,
                onValueChange = {
                    customMinutes = it
                    customRejected = false
                },
                singleLine = true,
                label = { Text(text = stringResource(R.string.player_sleep_timer_custom_hint)) },
                isError = customRejected,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val minutes = customSleepMinutes(customMinutes)
                    if (minutes == null) {
                        customRejected = true
                    } else {
                        onSelect(SleepTimerOption.AfterDuration(minutes))
                    }
                },
            ) {
                Text(text = stringResource(R.string.player_sleep_timer_custom_confirm))
            }
        }
        if (customRejected) {
            Text(
                text = stringResource(R.string.player_sleep_timer_custom_invalid),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (sleepTimerState != SleepTimerState.Off) {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.player_sleep_timer_cancel), color = Color.White)
            }
        }
    }
}

/** 定时暂停的固定选项：四段时长与播完本集。 */
private val fixedSleepTimerChoices = listOf(
    R.string.player_sleep_timer_10_minutes to SleepTimerOption.AfterDuration(10),
    R.string.player_sleep_timer_30_minutes to SleepTimerOption.AfterDuration(30),
    R.string.player_sleep_timer_1_hour to SleepTimerOption.AfterDuration(60),
    R.string.player_sleep_timer_2_hours to SleepTimerOption.AfterDuration(120),
    R.string.player_sleep_timer_end_of_episode to SleepTimerOption.EndOfEpisode,
)

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
