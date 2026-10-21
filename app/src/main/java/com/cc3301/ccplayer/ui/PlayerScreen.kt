package com.cc3301.ccplayer.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/** 顶栏内容高度；“更多”面板据此贴在标题栏下方。 */
private val PlayerTopBarHeight = 56.dp

/** “更多”面板为底部控制条预留的高度。 */
private val PlayerControlBarReserve = 224.dp

/** 顶栏边缘渐变：无实色底，只在屏幕顶部保留极淡的黑色过渡。 */
private val PlayerTopBarGradient = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.62f),
    0.42f to Color.Black.copy(alpha = 0.34f),
    1f to Color.Transparent,
)

/** 底栏边缘渐变：无实色底，只在屏幕底部保留极淡的黑色过渡。 */
private val PlayerBottomBarGradient = Brush.verticalGradient(
    0f to Color.Transparent,
    0.48f to Color.Black.copy(alpha = 0.46f),
    1f to Color.Black.copy(alpha = 0.78f),
)

/** 吞掉区域内触摸，避免落到下层手势层；不建立可点击语义。 */
private fun Modifier.blockPlayerGestures(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { change ->
                if (change.pressed) change.consume()
            }
        }
    }
}

/** 无涟漪点击。 */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

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

/** 缓冲指示：转圈与“正在缓冲”，首次打开叠在黑屏上，切集叠在冻结画面上。 */
@Composable
private fun BufferingIndicator(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            color = AppColors.Foreground,
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = stringResource(R.string.player_buffering),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Foreground,
            modifier = Modifier.padding(top = AppSpacing.Sm),
        )
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
    sleepTimerOption: SleepTimerOption?,
    levelControls: PlayerLevelControls,
    surfaceCallback: MpvSurfaceView.Callback,
    videoAspectRatio: Double?,
    switching: Boolean,
    buffering: Boolean,
    initialLoading: Boolean,
    freezeFrame: Bitmap?,
    subtitleTracks: List<SubtitleTrack>,
    externalSubtitles: List<MediaFile>,
    subtitleSelection: SubtitleSelection,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPlaylistSelect: (MediaFile) -> Unit,
    onSelectDecodeMode: (DecodeMode) -> Unit,
    onSelectLoopMode: (LoopMode) -> Unit,
    onSelectOrientationMode: (OrientationMode) -> Unit,
    onToggleAutoPlayNext: () -> Unit,
    onSelectSleepTimer: (SleepTimerOption) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSelectSubtitle: (SubtitleMenuItem) -> Unit,
    onToggleRotation: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lock = remember { PlayerLock() }
    val controls = remember { PlayerControlsVisibility() }
    var playlistVisible by remember { mutableStateOf(false) }
    var subtitleMenuVisible by remember { mutableStateOf(false) }
    var moreVisible by remember { mutableStateOf(false) }
    var volumeHint by remember { mutableStateOf<Float?>(null) }
    var seekHint by remember { mutableStateOf<SeekHint?>(null) }
    val locked = lock.locked
    val controlsVisible = controls.visible
    val panelOpen = playlistVisible || subtitleMenuVisible || moreVisible

    LaunchedEffect(panelOpen) {
        controls.updatePanelOpen(panelOpen)
    }
    LaunchedEffect(controls.visible, controls.panelOpen, controls.lastInteractionAtMillis, initialLoading) {
        if (initialLoading) return@LaunchedEffect
        while (controls.visible && !controls.panelOpen) {
            val remaining = controls.millisUntilAutoHide()
            if (remaining < 0L) break
            if (remaining == 0L) {
                controls.hideIfExpired()
                break
            }
            delay(remaining)
        }
    }

    LaunchedEffect(lock.locked, lock.unlockVisible, lock.unlockShownAtMillis) {
        if (lock.locked && lock.unlockVisible) {
            delay(UNLOCK_AUTO_HIDE_MILLIS)
            lock.hideUnlockIfExpired()
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
        val morePanelWidth = (maxWidth - 72.dp).coerceIn(1.dp, 320.dp)
        val morePanelMaxHeight = (maxHeight - PlayerTopBarHeight - PlayerControlBarReserve)
            .coerceAtLeast(72.dp)
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
        // 黑底画在视频视图之后：首次缓冲，或切集抓帧失败时，不露出未就绪的 Surface。
        if ((buffering && !switching) || (switching && freezeFrame == null)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        }
        if (switching && freezeFrame != null) {
            Image(
                bitmap = freezeFrame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (buffering) {
            BufferingIndicator(modifier = Modifier.align(Alignment.Center))
        }
        PlayerGestureLayer(
            locked = locked,
            panelOpen = panelOpen,
            initialLoading = initialLoading,
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            levelControls = levelControls,
            onTogglePlayPause = {
                controls.onInteraction()
                onTogglePlayPause()
            },
            onSeekBy = {
                controls.onInteraction()
                onSeekBy(it)
            },
            onSeekPreview = {
                controls.onInteraction()
                onSeekPreview(it)
            },
            onSeekCommit = {
                controls.onInteraction()
                onSeekCommit()
            },
            onToggleControls = { controls.toggle() },
            onUserInteraction = { controls.onInteraction() },
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
        // 锁定态只响应点画面：重新显示解锁按钮，不触发任何手势。
        if (locked) {
            val lockedHint = stringResource(R.string.player_screen_locked_hint)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .noRippleClickable { lock.tapScreen() }
                    .semantics { contentDescription = lockedHint },
            )
        }
        val backAllowed = inputAllowed(PlayerInput.Back, locked, initialLoading)
        val controlsAllowed = inputAllowed(PlayerInput.ControlBar, locked, initialLoading)
        if (backAllowed) {
            // 首次加载禁用画面手势，控制层即使已隐藏也必须保留返回入口。
            if (controlsVisible || initialLoading) {
                PlayerTopBar(
                    title = playlist.getOrNull(currentIndex)?.name.orEmpty(),
                    moreVisible = moreVisible,
                    controlsEnabled = controlsAllowed,
                    onBack = onClose,
                    onRotate = {
                        controls.onInteraction()
                        onToggleRotation()
                    },
                    onToggleMore = {
                        controls.onInteraction()
                        val opening = !moreVisible
                        moreVisible = opening
                        if (opening) {
                            playlistVisible = false
                            subtitleMenuVisible = false
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart),
                )
                if (controlsAllowed && moreVisible) {
                    MorePanel(
                        decodeMode = decodeMode,
                        loopMode = loopMode,
                        orientationMode = orientationMode,
                        autoPlayNext = autoPlayNext,
                        sleepTimerState = sleepTimerState,
                        selectedSleepTimerOption = sleepTimerOption,
                        onSelectDecodeMode = onSelectDecodeMode,
                        onSelectLoopMode = onSelectLoopMode,
                        onSelectOrientationMode = onSelectOrientationMode,
                        onToggleAutoPlayNext = onToggleAutoPlayNext,
                        onSelectSleepTimer = { option ->
                            onSelectSleepTimer(option)
                            moreVisible = false
                        },
                        onCancelSleepTimer = {
                            onCancelSleepTimer()
                            moreVisible = false
                        },
                        panelWidth = morePanelWidth,
                        panelMaxHeight = morePanelMaxHeight,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .safeDrawingPadding()
                            .padding(top = PlayerTopBarHeight, end = AppSpacing.Sm),
                    )
                }
                if (controlsAllowed) {
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
                        onTogglePlayPause = {
                            controls.onInteraction()
                            onTogglePlayPause()
                        },
                        onPrevious = {
                            controls.onInteraction()
                            onPrevious()
                        },
                        onNext = {
                            controls.onInteraction()
                            onNext()
                        },
                        onSeekPreview = {
                            controls.onInteraction()
                            onSeekPreview(it)
                        },
                        onSeekCommit = {
                            controls.onInteraction()
                            onSeekCommit()
                        },
                        onPlaylistSelect = onPlaylistSelect,
                        onTogglePlaylist = {
                            controls.onInteraction()
                            val opening = !playlistVisible
                            playlistVisible = opening
                            if (opening) {
                                subtitleMenuVisible = false
                                moreVisible = false
                            }
                        },
                        onToggleSubtitleMenu = {
                            controls.onInteraction()
                            val opening = !subtitleMenuVisible
                            subtitleMenuVisible = opening
                            if (opening) {
                                playlistVisible = false
                                moreVisible = false
                            }
                        },
                        onLock = { lock.tapLockButton() },
                        onSelectSubtitle = onSelectSubtitle,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        } else {
            if (lock.unlockVisible) {
                UnlockButton(
                    onTap = {
                        lock.tapLockButton()
                        controls.onInteraction()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .safeDrawingPadding()
                        .padding(AppSpacing.Lg),
                )
            }
        }
    }
}

/** 手势层：单击显隐控制条、三分区双击、两侧上下拖调音量、中间左右拖跳转。 */
@Composable
private fun PlayerGestureLayer(
    locked: Boolean,
    panelOpen: Boolean,
    initialLoading: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    levelControls: PlayerLevelControls,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onToggleControls: () -> Unit,
    onUserInteraction: () -> Unit,
    onVolumeHint: (Float?) -> Unit,
    onSeekHint: (SeekHint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = gesturesAllowed(locked, panelOpen, initialLoading)
    // 回调与播放位置取最新一份，避免手势层在位置变化时重启。
    val latestOnTogglePlayPause = rememberUpdatedState(onTogglePlayPause)
    val latestOnSeekBy = rememberUpdatedState(onSeekBy)
    val latestOnSeekPreview = rememberUpdatedState(onSeekPreview)
    val latestOnSeekCommit = rememberUpdatedState(onSeekCommit)
    val latestOnToggleControls = rememberUpdatedState(onToggleControls)
    val latestOnUserInteraction = rememberUpdatedState(onUserInteraction)
    val latestOnVolumeHint = rememberUpdatedState(onVolumeHint)
    val latestOnSeekHint = rememberUpdatedState(onSeekHint)
    val latestPosition = rememberUpdatedState(positionMillis)
    val latestDuration = rememberUpdatedState(durationMillis)
    val tapGesture = remember { PlayerTapGesture() }
    val dispatchTap: (TapGestureAction) -> Unit = { action ->
        when (action) {
            TapGestureAction.ToggleControls -> latestOnToggleControls.value()
            is TapGestureAction.DoubleTap -> {
                latestOnUserInteraction.value()
                when (val doubleTap = action.action) {
                    is DoubleTapAction.Seek -> latestOnSeekBy.value(doubleTap.deltaMillis)
                    DoubleTapAction.TogglePlayPause -> latestOnTogglePlayPause.value()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) {
                    tapGesture.cancel()
                    return@pointerInput
                }
                detectPlayerTaps(tapGesture, dispatchTap)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val threshold = VOLUME_DRAG_THRESHOLD_DP.dp.toPx()
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val drag = PlayerDragGesture(threshold, touchSlop)
                    drag.start(gestureZone(down.position.x, size.width))
                    var seekDrag: PlayerSeekDrag? = null
                    var interactionNotified = false
                    levelControls.beginDrag()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            val delta = change.position - change.previousPosition
                            val update = drag.update(delta.x, delta.y)
                            if (update.consume) {
                                change.consume()
                                tapGesture.cancel()
                            }
                            if (!interactionNotified && update.gesture != null) {
                                interactionNotified = true
                                latestOnUserInteraction.value()
                            }
                            when (update.gesture) {
                                DragGesture.Volume -> if (update.volumeDragY != 0f) {
                                    latestOnVolumeHint.value(
                                        levelControls.dragVolume(update.volumeDragY, size.height),
                                    )
                                }

                                DragGesture.Seek -> {
                                    val seek = seekDrag ?: PlayerSeekDrag(
                                        baseMillis = latestPosition.value,
                                        durationMillis = latestDuration.value,
                                        width = size.width,
                                    ).also { seekDrag = it }
                                    seek.preview(update.seekDragX)?.let { target ->
                                        latestOnSeekPreview.value(target)
                                        latestOnSeekHint.value(SeekHint(target, target - seek.baseMillis))
                                    }
                                }

                                null -> Unit
                            }
                        }
                        if (drag.gesture == DragGesture.Seek && seekDrag?.finish() != null) {
                            latestOnSeekCommit.value()
                        }
                    } finally {
                        latestOnVolumeHint.value(null)
                        latestOnSeekHint.value(null)
                    }
                }
            },
    )
}

/** 单击/双击统一交给 [PlayerTapGesture]：单击延迟到双击窗口结束，第二次按下转为拖动时明确取消。 */
private suspend fun PointerInputScope.detectPlayerTaps(
    gesture: PlayerTapGesture,
    onAction: (TapGestureAction) -> Unit,
) {
    awaitEachGesture {
        val firstDown = awaitFirstDown()
        gesture.press(firstDown.uptimeMillis, firstDown.position)?.let(onAction)
        val firstUp = waitForUpOrCancellation()
        if (firstUp == null) {
            gesture.cancel()
            return@awaitEachGesture
        }
        gesture.release(firstUp.uptimeMillis, size.width)?.let(onAction)
        val secondDown = withTimeoutOrNull(DOUBLE_TAP_WINDOW_MILLIS) {
            awaitFirstDown(requireUnconsumed = false)
        }
        if (secondDown == null) {
            gesture.timeout(firstUp.uptimeMillis + DOUBLE_TAP_WINDOW_MILLIS)?.let(onAction)
            return@awaitEachGesture
        }
        gesture.press(secondDown.uptimeMillis, secondDown.position)?.let(onAction)
        val secondUp = waitForUpOrCancellation()
        if (secondUp == null) {
            gesture.cancel()
        } else {
            gesture.release(secondUp.uptimeMillis, size.width)?.let(onAction)
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
 * 叠加在画面之上的标题栏：左侧返回与文件名，右侧转屏与更多。
 * 无实色底，只保留屏幕顶部的极淡渐变；整条区域吞掉手势，避免落到下面的手势层上。
 */
@Composable
private fun PlayerTopBar(
    title: String,
    moreVisible: Boolean,
    controlsEnabled: Boolean,
    onBack: () -> Unit,
    onRotate: () -> Unit,
    onToggleMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(PlayerTopBarGradient)
            .blockPlayerGestures()
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlayerTopBarHeight)
                .padding(horizontal = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
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
                    .weight(1f)
                    .padding(end = AppSpacing.Xs),
            )
            IconButton(onClick = onRotate, enabled = controlsEnabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_screen_rotation),
                    contentDescription = stringResource(R.string.player_rotate),
                    tint = if (controlsEnabled) AppColors.Foreground else AppColors.Faint.copy(alpha = 0.45f),
                )
            }
            IconButton(onClick = onToggleMore, enabled = controlsEnabled) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.player_more),
                    tint = if (controlsEnabled) playerToolColor(moreVisible) else AppColors.Faint.copy(alpha = 0.45f),
                )
            }
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

/** 锁定态下唯一可见的控件：点一次立即解锁，无背景与强调色。 */
@Composable
private fun UnlockButton(onTap: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onTap, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_player_lock_open),
            contentDescription = stringResource(R.string.player_unlock),
            tint = AppColors.Foreground,
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
    onPlaylistSelect: (MediaFile) -> Unit,
    onTogglePlaylist: () -> Unit,
    onToggleSubtitleMenu: () -> Unit,
    onLock: () -> Unit,
    onSelectSubtitle: (SubtitleMenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 图标条有意全宽贴边：窄屏压缩需要全部可用宽度；进度条与面板仍保留水平内边距。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PlayerBottomBarGradient)
            .blockPlayerGestures()
            .safeDrawingPadding()
            .padding(top = 24.dp, bottom = AppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        if (playlistVisible) {
            PlaylistPanel(
                playlist = playlist,
                currentIndex = currentIndex,
                onSelect = onPlaylistSelect,
                modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            )
        }
        if (subtitleMenuVisible) {
            SubtitlePanel(
                menu = subtitleMenuItems(subtitleTracks, externalSubtitles, subtitleSelection),
                onSelect = onSelectSubtitle,
                modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            )
        }
        SeekBar(
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            previewMillis = seekPreviewMillis,
            onPreview = onSeekPreview,
            onCommit = onSeekCommit,
            modifier = Modifier.padding(horizontal = AppSpacing.Lg),
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
            onLock = onLock,
        )
    }
}

/** 底部图标条：锁定键在左下角，播放键严格居中，字幕与播放列表在右下角。 */
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
    onLock: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 记住上一次排布，宽度在阈值附近变化时不来回切换布局。
        var previousRightStacked by remember { mutableStateOf(false) }
        val metrics = playerIconBarMetrics(maxWidth, previousRightStacked)
        SideEffect { previousRightStacked = metrics.rightStacked }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.barHeight),
        ) {
            // 左下角锁定键与上一集/播放/下一集同一条水平线。
            PlayerIconButton(
                icon = R.drawable.ic_player_lock_closed,
                contentDescription = stringResource(R.string.player_lock),
                onClick = onLock,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(metrics.sideButtonSize),
            )
            // 上一集在播放键左侧、下一集在右侧，播放键始终居中。
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.gap),
            ) {
                PlayerIconButton(
                    icon = R.drawable.ic_player_previous,
                    contentDescription = stringResource(R.string.player_previous),
                    onClick = onPrevious,
                    enabled = canGoPrevious,
                    modifier = Modifier.size(metrics.previousNextSize),
                    iconSize = PlayerTransportIconSize,
                )
                PlayerIconButton(
                    icon = if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                    contentDescription = stringResource(
                        if (playing) R.string.player_pause else R.string.player_play,
                    ),
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(metrics.playPauseSize),
                    iconSize = PlayerPlayPauseIconSize,
                )
                PlayerIconButton(
                    icon = R.drawable.ic_player_next,
                    contentDescription = stringResource(R.string.player_next),
                    onClick = onNext,
                    enabled = canGoNext,
                    modifier = Modifier.size(metrics.previousNextSize),
                    iconSize = PlayerTransportIconSize,
                )
            }
            // 字幕紧挨播放列表放在右下区域；极窄屏改为上下排列，避免与三键重叠。
            val subtitleButton: @Composable () -> Unit = {
                PlayerIconButton(
                    icon = R.drawable.ic_player_subtitles,
                    contentDescription = stringResource(R.string.player_subtitles),
                    onClick = onToggleSubtitles,
                    active = subtitleMenuVisible,
                    modifier = Modifier.size(metrics.sideButtonSize),
                )
            }
            val playlistButton: @Composable () -> Unit = {
                PlayerIconButton(
                    icon = R.drawable.ic_player_playlist,
                    contentDescription = stringResource(R.string.player_playlist),
                    onClick = onTogglePlaylist,
                    active = playlistVisible,
                    modifier = Modifier.size(metrics.sideButtonSize),
                )
            }
            if (metrics.rightStacked) {
                Column(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalArrangement = Arrangement.Center,
                ) {
                    subtitleButton()
                    playlistButton()
                }
            } else {
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    subtitleButton()
                    playlistButton()
                }
            }
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
    iconSize: Dp = PlayerSideIconSize,
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
            modifier = Modifier.size(iconSize),
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
    modifier: Modifier = Modifier,
) {
    val maximum = durationMillis.coerceAtLeast(1L)
    val shown = (previewMillis ?: positionMillis).coerceIn(0L, maximum)
    Column(modifier = modifier.fillMaxWidth()) {
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
    panelWidth: Dp,
    panelMaxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<MoreSection?>(null) }

    fun toggle(section: MoreSection) {
        expanded = if (expanded == section) null else section
    }

    Column(
        modifier = modifier
            .width(panelWidth)
            .heightIn(max = panelMaxHeight)
            .playerPanel()
            .blockPlayerGestures()
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
                    checked = isSleepTimerOptionChecked(option.value, selectedSleepTimerOption),
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
    val expandedState = stringResource(
        if (expanded) R.string.player_more_expanded else R.string.player_more_collapsed,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick)
            .semantics { stateDescription = expandedState }
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
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = checked,
                onClick = onClick,
                role = Role.RadioButton,
                interactionSource = interaction,
                indication = null,
            )
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
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = { onToggle() },
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
            )
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
            onCheckedChange = null,
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
    onSelect: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
                        .noRippleClickable { onSelect(media) }
                        .padding(horizontal = AppSpacing.Md, vertical = 10.dp),
                )
            }
        }
    }
}

/** 字幕菜单：内嵌轨道、可匹配外挂、关闭，当前生效项用强调色。 */
@Composable
private fun SubtitlePanel(
    menu: List<SubtitleMenuItem>,
    onSelect: (SubtitleMenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
                        .noRippleClickable { onSelect(item) }
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
