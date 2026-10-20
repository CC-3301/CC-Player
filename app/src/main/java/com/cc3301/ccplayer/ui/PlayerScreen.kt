package com.cc3301.ccplayer.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import com.cc3301.ccplayer.playback.SubtitleSelection
import com.cc3301.ccplayer.playback.SubtitleTrack
import com.cc3301.ccplayer.playback.customSleepMinutes
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.ui.theme.AppColors
import com.cc3301.ccplayer.ui.theme.AppRadius
import com.cc3301.ccplayer.ui.theme.AppSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** 播放器浮层的半透明面板与圆角，叠在画面上也保持主题一致。 */
private val PlayerPanelColor = AppColors.SurfaceHigh.copy(alpha = 0.96f)
private val PlayerPanelShape = RoundedCornerShape(AppRadius.Lg)

/** 播放器浮层统一面板：半透明深色底、细边框与统一圆角。 */
private fun Modifier.playerPanel(): Modifier = this
    .background(PlayerPanelColor, PlayerPanelShape)
    .border(1.dp, AppColors.Line, PlayerPanelShape)

/** 工具按钮颜色：面板展开或定时已设置时用强调色，其余用弱化文字色。 */
private fun playerToolColor(active: Boolean): Color =
    if (active) AppColors.Accent else AppColors.Muted

/** 播放器次级文字按钮：统一字号、内边距与禁用态。 */
@Composable
private fun PlayerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = AppColors.Foreground,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = AppColors.Faint.copy(alpha = 0.45f),
        ),
        contentPadding = PaddingValues(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs),
    ) {
        Text(text = text, style = style, maxLines = 1)
    }
}

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
    subtitleTracks: List<SubtitleTrack>,
    externalSubtitles: List<MediaFile>,
    subtitleSelection: SubtitleSelection,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPlaylistSelect: (Int) -> Unit,
    onSelectDecodeMode: (DecodeMode) -> Unit,
    onSelectLoopMode: (LoopMode) -> Unit,
    onSelectOrientationMode: (OrientationMode) -> Unit,
    onToggleAutoPlayNext: () -> Unit,
    onSelectSleepTimer: (SleepTimerOption) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSelectSubtitle: (SubtitleMenuItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lock = remember { PlayerLock() }
    var controlsVisible by remember { mutableStateOf(true) }
    var playlistVisible by remember { mutableStateOf(false) }
    var subtitleMenuVisible by remember { mutableStateOf(false) }
    var moreVisible by remember { mutableStateOf(false) }
    var sleepTimerChoice by remember { mutableStateOf<SleepTimerOption?>(null) }
    var volumeHint by remember { mutableStateOf<Float?>(null) }
    var seekHint by remember { mutableStateOf<SeekHint?>(null) }
    val locked = lock.locked
    val panelOpen = playlistVisible || subtitleMenuVisible || moreVisible

    LaunchedEffect(lock.unlockPending) {
        if (lock.unlockPending) {
            delay(UNLOCK_CONFIRM_WINDOW_MILLIS)
            lock.expireUnlock()
        }
    }

    BoxWithConstraints(modifier = modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.scrim)) {
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
            panelOpen = panelOpen,
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            levelControls = levelControls,
            onTogglePlayPause = onTogglePlayPause,
            onSeekBy = onSeekBy,
            onSeekPreview = onSeekPreview,
            onSeekCommit = onSeekCommit,
            onToggleControls = { controlsVisible = !controlsVisible },
            onVolumeHint = { volumeHint = it },
            onSeekHint = { seekHint = it },
            modifier = Modifier.fillMaxSize(),
        )
        volumeHint?.let { fraction ->
            LevelHintLabel(fraction = fraction, modifier = Modifier.align(Alignment.Center))
        }
        seekHint?.let { hint ->
            SeekHintLabel(hint = hint, modifier = Modifier.align(Alignment.Center))
        }
        if (inputAllowed(PlayerInput.ControlBar, locked)) {
            if (controlsVisible) {
                PlayerTopBar(
                    title = playlist.getOrNull(currentIndex)?.name.orEmpty(),
                    moreVisible = moreVisible,
                    onBack = onClose,
                    onToggleMore = {
                        moreVisible = !moreVisible
                        if (!moreVisible) {
                            playlistVisible = false
                            subtitleMenuVisible = false
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart),
                )
                LockButton(
                    onLock = { lock.tapLockButton() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .safeDrawingPadding()
                        .padding(start = AppSpacing.Sm, top = 56.dp),
                )
                if (moreVisible) {
                    MorePanel(
                        decodeMode = decodeMode,
                        loopMode = loopMode,
                        orientationMode = orientationMode,
                        autoPlayNext = autoPlayNext,
                        sleepTimerState = sleepTimerState,
                        selectedSleepTimerOption = sleepTimerChoice,
                        onSelectDecodeMode = onSelectDecodeMode,
                        onSelectLoopMode = onSelectLoopMode,
                        onSelectOrientationMode = onSelectOrientationMode,
                        onToggleAutoPlayNext = onToggleAutoPlayNext,
                        onSelectSleepTimer = { option ->
                            sleepTimerChoice = option
                            onSelectSleepTimer(option)
                            moreVisible = false
                        },
                        onCancelSleepTimer = {
                            sleepTimerChoice = null
                            onCancelSleepTimer()
                            moreVisible = false
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .safeDrawingPadding()
                            .padding(top = 56.dp, end = AppSpacing.Sm),
                    )
                }
                Controls(
                    state = state,
                    progress = progress,
                    seekPreviewMillis = seekPreviewMillis,
                    playlist = playlist,
                    currentIndex = currentIndex,
                    subtitleTracks = subtitleTracks,
                    externalSubtitles = externalSubtitles,
                    subtitleSelection = subtitleSelection,
                    playlistVisible = playlistVisible,
                    subtitleMenuVisible = subtitleMenuVisible,
                    onTogglePlayPause = onTogglePlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeekPreview = onSeekPreview,
                    onSeekCommit = onSeekCommit,
                    onPlaylistSelect = onPlaylistSelect,
                    onTogglePlaylist = { playlistVisible = !playlistVisible },
                    onToggleSubtitleMenu = { subtitleMenuVisible = !subtitleMenuVisible },
                    onSelectSubtitle = onSelectSubtitle,
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

/** 手势层：单击显隐控制条、三分区双击、两侧上下拖调音量、中间左右拖跳转。 */
@Composable
private fun PlayerGestureLayer(
    locked: Boolean,
    panelOpen: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    levelControls: PlayerLevelControls,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onToggleControls: () -> Unit,
    onVolumeHint: (Float?) -> Unit,
    onSeekHint: (SeekHint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = gesturesAllowed(locked, panelOpen)
    // 回调与播放位置取最新一份，避免手势层在位置变化时重启。
    val latestOnTogglePlayPause = rememberUpdatedState(onTogglePlayPause)
    val latestOnSeekBy = rememberUpdatedState(onSeekBy)
    val latestOnSeekPreview = rememberUpdatedState(onSeekPreview)
    val latestOnSeekCommit = rememberUpdatedState(onSeekCommit)
    val latestOnToggleControls = rememberUpdatedState(onToggleControls)
    val latestOnVolumeHint = rememberUpdatedState(onVolumeHint)
    val latestOnSeekHint = rememberUpdatedState(onSeekHint)
    val latestPosition = rememberUpdatedState(positionMillis)
    val latestDuration = rememberUpdatedState(durationMillis)

    Box(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectPlayerTaps(
                    onTap = { latestOnToggleControls.value() },
                    onDoubleTap = { offset ->
                        when (val action = doubleTapAction(offset.x, size.width)) {
                            is DoubleTapAction.Seek -> latestOnSeekBy.value(action.deltaMillis)
                            DoubleTapAction.TogglePlayPause -> latestOnTogglePlayPause.value()
                        }
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val threshold = VOLUME_DRAG_THRESHOLD_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val zone = gestureZone(down.position.x, size.width)
                    val seekBaseMillis = latestPosition.value
                    var gesture: DragGesture? = null
                    var totalX = 0f
                    var totalY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) break
                        val delta = change.position - change.previousPosition
                        totalX += delta.x
                        totalY += delta.y
                        if (gesture == null) {
                            gesture = dragGesture(zone, totalX, totalY, threshold)
                        }
                        if (gesture != null) {
                            change.consume()
                        }
                        when (gesture) {
                            DragGesture.Volume -> if (delta.y != 0f) {
                                latestOnVolumeHint.value(levelControls.dragVolume(delta.y, size.height))
                            }

                            DragGesture.Seek -> {
                                val duration = latestDuration.value
                                if (duration > 0) {
                                    val target = (seekBaseMillis + seekDeltaMillis(totalX, size.width))
                                        .coerceIn(0L, duration)
                                    latestOnSeekPreview.value(target)
                                    latestOnSeekHint.value(SeekHint(target, target - seekBaseMillis))
                                }
                            }

                            null -> Unit
                        }
                    }
                    if (gesture == DragGesture.Seek) latestOnSeekCommit.value()
                    latestOnVolumeHint.value(null)
                    latestOnSeekHint.value(null)
                }
            },
    )
}

/** 300ms 内两次点击为双击；单击动作延迟到双击窗口结束才触发。 */
private suspend fun PointerInputScope.detectPlayerTaps(
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown()
        val firstUp = waitForUpOrCancellation() ?: return@awaitEachGesture
        val secondDown = withTimeoutOrNull(DOUBLE_TAP_WINDOW_MILLIS) {
            awaitFirstDown(requireUnconsumed = false)
        }
        if (secondDown == null) {
            onTap(firstUp.position)
            return@awaitEachGesture
        }
        val secondUp = waitForUpOrCancellation()
        if (secondUp != null) {
            onDoubleTap(secondUp.position)
        }
    }
}

private data class SeekHint(val targetMillis: Long, val deltaMillis: Long)

/** 拖动音量时的百分比提示。 */
@Composable
private fun LevelHintLabel(fraction: Float, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.player_volume_hint, (fraction * 100).roundToInt()),
        style = MaterialTheme.typography.titleMedium,
        color = AppColors.Foreground,
        modifier = modifier
            .playerPanel()
            .padding(horizontal = AppSpacing.Xl, vertical = AppSpacing.Sm),
    )
}

/** 拖动进度时的目标时间与增减量提示。 */
@Composable
private fun SeekHintLabel(hint: SeekHint, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            R.string.player_seek_hint,
            formatDuration(hint.targetMillis),
            signedDuration(hint.deltaMillis),
        ),
        style = MaterialTheme.typography.titleMedium,
        color = AppColors.Foreground,
        modifier = modifier
            .playerPanel()
            .padding(horizontal = AppSpacing.Xl, vertical = AppSpacing.Sm),
    )
}

private fun signedDuration(millis: Long): String =
    (if (millis < 0) "-" else "+") + formatDuration(abs(millis))

/**
 * 叠加在画面之上的标题栏：左侧返回、中间文件名、右侧更多。
 * 透明底，不占布局高度；整条区域吞掉手势，避免落到下面的手势层上。
 */
@Composable
private fun PlayerTopBar(
    title: String,
    moreVisible: Boolean,
    onBack: () -> Unit,
    onToggleMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .safeDrawingPadding()
            .padding(horizontal = AppSpacing.Sm, vertical = AppSpacing.Xs),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.player_title_back),
                tint = AppColors.Foreground,
            )
        }
        Text(
            text = title,
            style = playerOverlayTextStyle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp),
        )
        IconButton(onClick = onToggleMore, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.player_more),
                tint = playerToolColor(moreVisible),
            )
        }
    }
}

/** 透明底上的白字：阴影保证画面亮时也看得清。 */
@Composable
private fun playerOverlayTextStyle(): TextStyle = MaterialTheme.typography.titleMedium.copy(
    color = AppColors.Foreground,
    shadow = Shadow(
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f),
        offset = Offset(0f, 2f),
        blurRadius = 6f,
    ),
)

/** 锁定按钮：位于顶部返回按钮下方。 */
@Composable
private fun LockButton(onLock: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onLock,
        modifier = modifier.playerPanel(),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = stringResource(R.string.player_lock),
            tint = AppColors.Foreground,
        )
    }
}

/** 锁定态下唯一可见的控件：点两次解锁，第一次点后图标转为强调色。 */
@Composable
private fun UnlockButton(unlockPending: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onTap,
        modifier = modifier
            .safeDrawingPadding()
            .padding(AppSpacing.Md)
            .playerPanel(),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = stringResource(
                if (unlockPending) R.string.player_unlock_confirm else R.string.player_unlock,
            ),
            tint = if (unlockPending) AppColors.Accent else AppColors.Foreground,
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
    subtitleTracks: List<SubtitleTrack>,
    externalSubtitles: List<MediaFile>,
    subtitleSelection: SubtitleSelection,
    playlistVisible: Boolean,
    subtitleMenuVisible: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onPlaylistSelect: (Int) -> Unit,
    onTogglePlaylist: () -> Unit,
    onToggleSubtitleMenu: () -> Unit,
    onSelectSubtitle: (SubtitleMenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrim = MaterialTheme.colorScheme.scrim
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(scrim.copy(alpha = 0.55f), scrim.copy(alpha = 0.88f)),
                ),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .safeDrawingPadding()
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        if (playlistVisible) {
            PlaylistPanel(
                playlist = playlist,
                currentIndex = currentIndex,
                onSelect = onPlaylistSelect,
            )
        }
        if (subtitleMenuVisible) {
            SubtitlePanel(
                menu = subtitleMenuItems(subtitleTracks, externalSubtitles, subtitleSelection),
                onSelect = onSelectSubtitle,
            )
        }
        SeekBar(
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            previewMillis = seekPreviewMillis,
            onPreview = onSeekPreview,
            onCommit = onSeekCommit,
        )
        PlayerIconBar(
            playing = state == PlaybackState.Playing,
            canGoPrevious = currentIndex > 0,
            canGoNext = currentIndex in 0 until playlist.lastIndex,
            subtitleMenuVisible = subtitleMenuVisible,
            playlistVisible = playlistVisible,
            onTogglePlayPause = onTogglePlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onToggleSubtitles = onToggleSubtitleMenu,
            onTogglePlaylist = onTogglePlaylist,
        )
    }
}

/** 底部图标条：播放/暂停严格居中，上一集在左，下一集、字幕与播放列表在右。 */
@Composable
private fun PlayerIconBar(
    playing: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    subtitleMenuVisible: Boolean,
    playlistVisible: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleSubtitles: () -> Unit,
    onTogglePlaylist: () -> Unit,
) {
    Box(modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)) {
        PlayerIconButton(
            icon = R.drawable.ic_player_previous,
            contentDescription = stringResource(R.string.player_previous),
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        PlayerIconButton(
            icon = if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
            contentDescription = stringResource(
                if (playing) R.string.player_pause else R.string.player_play,
            ),
            onClick = onTogglePlayPause,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            PlayerIconButton(
                icon = R.drawable.ic_player_next,
                contentDescription = stringResource(R.string.player_next),
                onClick = onNext,
                enabled = canGoNext,
            )
            PlayerIconButton(
                icon = R.drawable.ic_player_subtitles,
                contentDescription = stringResource(R.string.player_subtitles),
                onClick = onToggleSubtitles,
                active = subtitleMenuVisible,
            )
            PlayerIconButton(
                icon = R.drawable.ic_player_playlist,
                contentDescription = stringResource(R.string.player_playlist),
                onClick = onTogglePlaylist,
                active = playlistVisible,
            )
        }
    }
}

/** 播放器图标按钮：只有图标，禁用或展开时改变颜色。 */
@Composable
private fun PlayerIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = when {
                !enabled -> AppColors.Faint.copy(alpha = 0.45f)
                active -> AppColors.Accent
                else -> AppColors.Foreground
            },
            modifier = Modifier.size(26.dp),
        )
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
                    color = AppColors.Foreground,
                    modifier = Modifier
                        .background(PlayerPanelColor, RoundedCornerShape(AppRadius.Sm))
                        .border(1.dp, AppColors.Line, RoundedCornerShape(AppRadius.Sm))
                        .padding(horizontal = AppSpacing.Md, vertical = 2.dp),
                )
            }
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { onPreview(it.toLong()) },
            onValueChangeFinished = onCommit,
            valueRange = 0f..maximum.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onPrimary,
                activeTrackColor = AppColors.Accent,
                inactiveTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/**
 * “更多”面板：无标题栏，直接列出解码、循环、方向、自动连播与定时。
 * 解码、循环、方向与定时展开为带勾选的列表；自动连播保留开关。
 */
@Composable
private fun MorePanel(
    decodeMode: DecodeMode,
    loopMode: LoopMode,
    orientationMode: OrientationMode,
    autoPlayNext: Boolean,
    sleepTimerState: SleepTimerState,
    selectedSleepTimerOption: SleepTimerOption?,
    onSelectDecodeMode: (DecodeMode) -> Unit,
    onSelectLoopMode: (LoopMode) -> Unit,
    onSelectOrientationMode: (OrientationMode) -> Unit,
    onToggleAutoPlayNext: () -> Unit,
    onSelectSleepTimer: (SleepTimerOption) -> Unit,
    onCancelSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<MoreSection?>(null) }

    fun toggle(section: MoreSection) {
        expanded = if (expanded == section) null else section
    }

    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .heightIn(max = 420.dp)
            .playerPanel()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .verticalScroll(rememberScrollState()),
    ) {
        MoreChoiceRow(
            label = stringResource(R.string.player_decode),
            value = optionText(OptionLabel(decodeModeLabel(decodeMode))),
            expanded = expanded == MoreSection.Decode,
            onClick = { toggle(MoreSection.Decode) },
        )
        if (expanded == MoreSection.Decode) {
            decodeModeOptions.forEach { option ->
                MoreOptionRow(
                    label = optionText(option.label),
                    checked = isOptionChecked(option, decodeMode),
                    onClick = { onSelectDecodeMode(option.value) },
                )
            }
        }
        MoreChoiceRow(
            label = stringResource(R.string.player_loop),
            value = optionText(OptionLabel(loopModeLabel(loopMode))),
            expanded = expanded == MoreSection.Loop,
            onClick = { toggle(MoreSection.Loop) },
        )
        if (expanded == MoreSection.Loop) {
            loopModeOptions.forEach { option ->
                MoreOptionRow(
                    label = optionText(option.label),
                    checked = isOptionChecked(option, loopMode),
                    onClick = { onSelectLoopMode(option.value) },
                )
            }
        }
        MoreChoiceRow(
            label = stringResource(R.string.player_orientation),
            value = optionText(OptionLabel(orientationModeLabel(orientationMode))),
            expanded = expanded == MoreSection.Orientation,
            onClick = { toggle(MoreSection.Orientation) },
        )
        if (expanded == MoreSection.Orientation) {
            orientationModeOptions.forEach { option ->
                MoreOptionRow(
                    label = optionText(option.label),
                    checked = isOptionChecked(option, orientationMode),
                    onClick = { onSelectOrientationMode(option.value) },
                )
            }
        }
        MoreSwitchRow(
            label = stringResource(R.string.player_auto_play_next),
            checked = autoPlayNext,
            onToggle = onToggleAutoPlayNext,
        )
        MoreChoiceRow(
            label = stringResource(R.string.player_sleep_timer),
            value = stringResource(
                if (sleepTimerState == SleepTimerState.Off) {
                    R.string.player_sleep_timer_off
                } else {
                    R.string.player_sleep_timer_set
                },
            ),
            expanded = expanded == MoreSection.SleepTimer,
            onClick = { toggle(MoreSection.SleepTimer) },
        )
        if (expanded == MoreSection.SleepTimer) {
            sleepTimerOptions.forEach { option ->
                MoreOptionRow(
                    label = optionText(option.label),
                    checked = isSleepTimerOptionChecked(option.value, sleepTimerState) ||
                        option.value == selectedSleepTimerOption,
                    onClick = { onSelectSleepTimer(option.value) },
                )
            }
            CustomSleepTimerRow(onSelect = onSelectSleepTimer)
            if (sleepTimerState != SleepTimerState.Off) {
                PlayerTextButton(
                    text = stringResource(R.string.player_sleep_timer_cancel),
                    onClick = onCancelSleepTimer,
                    modifier = Modifier.padding(horizontal = AppSpacing.Md),
                )
            }
        }
    }
}

/** 面板根行：标题、当前取值与展开箭头。 */
@Composable
private fun MoreChoiceRow(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
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
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = AppColors.Faint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 展开后的勾选项。 */
@Composable
private fun MoreOptionRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = AppSpacing.Xxl, end = AppSpacing.Lg, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (checked) AppColors.Accent else AppColors.Foreground,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = AppColors.Accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 自动连播开关行。 */
@Composable
private fun MoreSwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
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
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = AppColors.AccentDeep,
                uncheckedThumbColor = AppColors.SwitchThumb,
                uncheckedTrackColor = AppColors.SurfaceHighest,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** 定时暂停的自定义分钟数输入。 */
@Composable
private fun CustomSleepTimerRow(onSelect: (SleepTimerOption) -> Unit) {
    var customMinutes by remember { mutableStateOf("") }
    var customRejected by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.Xxl, end = AppSpacing.Lg, top = AppSpacing.Xs, bottom = AppSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
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
            shape = RoundedCornerShape(AppRadius.Sm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppColors.Foreground,
                unfocusedTextColor = AppColors.Foreground,
                focusedBorderColor = AppColors.Accent,
                unfocusedBorderColor = AppColors.Line,
                focusedLabelColor = AppColors.Accent,
                unfocusedLabelColor = AppColors.Muted,
                cursorColor = AppColors.Accent,
                errorBorderColor = AppColors.Danger,
                errorLabelColor = AppColors.Danger,
                errorCursorColor = AppColors.Danger,
            ),
            modifier = Modifier.weight(1f),
        )
        PlayerTextButton(
            text = stringResource(R.string.player_sleep_timer_custom_confirm),
            onClick = {
                val minutes = customSleepMinutes(customMinutes)
                if (minutes == null) {
                    customRejected = true
                } else {
                    onSelect(SleepTimerOption.AfterDuration(minutes))
                }
            },
        )
    }
    if (customRejected) {
        Text(
            text = stringResource(R.string.player_sleep_timer_custom_invalid),
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.Danger,
            modifier = Modifier.padding(start = AppSpacing.Xxl, end = AppSpacing.Lg, bottom = AppSpacing.Sm),
        )
    }
}

/** 播放列表：点击即切换到对应媒体。 */
@Composable
private fun PlaylistPanel(
    playlist: List<MediaFile>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .playerPanel(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(playlist) { index, media ->
                Text(
                    text = media.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (index == currentIndex) AppColors.Accent else AppColors.Foreground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = AppSpacing.Md, vertical = 10.dp),
                )
            }
        }
    }
}

/** 字幕菜单：内嵌轨道、可匹配外挂、关闭，当前生效项用强调色。 */
@Composable
private fun SubtitlePanel(menu: List<SubtitleMenuItem>, onSelect: (SubtitleMenuItem) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .playerPanel(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items = menu) { item ->
                Text(
                    text = subtitleItemLabel(item),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.active) AppColors.Accent else AppColors.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item) }
                        .padding(horizontal = AppSpacing.Md, vertical = 10.dp),
                )
            }
        }
    }
}

/** 内嵌条目带「内嵌」前缀，外挂条目用文件名，关闭条目用固定文案。 */
@Composable
private fun subtitleItemLabel(item: SubtitleMenuItem): String = when (item) {
    is SubtitleMenuItem.Embedded -> stringResource(R.string.player_subtitle_embedded, item.label)
    is SubtitleMenuItem.External -> item.label
    is SubtitleMenuItem.Off -> stringResource(R.string.player_subtitle_off)
}
