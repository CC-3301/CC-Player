package com.cc3301.ccplayer.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.max
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

/** 播放列表抽屉：左侧透明、越靠右越深的低透明度黑色渐变。 */
private val PlayerPlaylistGradient = Brush.horizontalGradient(
    0f to Color(0x14020509),
    0.34f to Color(0x6B020509),
    1f to Color(0xB8020509),
)

/** 播放列表条目固定高度，用于把当前项滚到可视区域中间。 */
private val PlayerPlaylistItemHeight = 44.dp

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
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    noRippleClickable(onClick, enabled = true)

/** 无涟漪点击；[enabled] 为假时不响应。 */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit, enabled: Boolean): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
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
    restoring: Boolean,
    buffering: Boolean,
    initialLoading: Boolean,
    freezeFrame: Bitmap?,
    backgroundRestoreFrame: Bitmap?,
    holdBlack: Boolean,
    subtitleTracks: List<SubtitleTrack>,
    externalSubtitles: List<MediaFile>,
    subtitleSelection: SubtitleSelection,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onSeekBy: (Long) -> Long,
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
    val panels = remember { PlayerPanelState() }
    var volumeHint by remember { mutableStateOf<Float?>(null) }
    var seekHint by remember { mutableStateOf<SeekHint?>(null) }
    val mediaKey = playlist.getOrNull(currentIndex)?.location.orEmpty()
    val doubleTapFeedback = remember(mediaKey) { PlayerDoubleTapFeedback() }
    val locked = lock.locked
    val controlsVisible = controls.visible
    val panelOpen = panels.anyOpen
    val playlistVisible = panels.playlistOpen
    val subtitleMenuVisible = panels.subtitlesOpen
    val moreVisible = panels.moreOpen

    // 首次加载与切集缓冲期间控制条保持可见：首次加载只允许返回，切集允许返回与上下集。
    LaunchedEffect(switching, initialLoading) {
        // 加载阶段变化时重新计时；加载结束进入空闲后从此刻起算自动隐藏。
        controls.onInteraction()
        panels.closeWhenBlocked(switching, initialLoading)
    }

    LaunchedEffect(panelOpen) {
        controls.updatePanelOpen(panelOpen)
    }
    LaunchedEffect(controls.visible, controls.panelOpen, controls.lastInteractionAtMillis, switching, initialLoading) {
        // 加载全程不自动隐藏，避免切集期间控制条消失。
        val holdVisible = switching || initialLoading
        while (controls.visible && !controls.panelOpen) {
            val remaining = controls.millisUntilAutoHide(holdVisible)
            if (remaining < 0L) break
            if (remaining == 0L) {
                controls.hideIfExpired(holdVisible)
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

    LaunchedEffect(doubleTapFeedback.left, doubleTapFeedback.right) {
        while (doubleTapFeedback.left != null || doubleTapFeedback.right != null) {
            val remaining = doubleTapFeedback.millisUntilExpire() ?: break
            delay(remaining)
            doubleTapFeedback.expire()
        }
    }

    // 切集、加载、恢复、面板或锁定状态切换时清空手势反馈，避免盖在缓冲层上。
    LaunchedEffect(switching, initialLoading, restoring, panelOpen, locked) {
        if (doubleTapFeedbackBlocked(locked, panelOpen, switching, initialLoading, restoring)) {
            doubleTapFeedback.reset()
            seekHint = null
            volumeHint = null
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
        // 黑底画在视频视图之后：首次缓冲、切集无冻结帧，或片尾恢复就绪后保持黑底。
        if (holdBlack || (buffering && !switching) || (switching && freezeFrame == null)) {
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
        // 后台回来先盖住离开前的画面；播放核心重新出帧后由控制层撤掉这一层。
        if (!switching && !holdBlack && backgroundRestoreFrame != null) {
            Image(
                bitmap = backgroundRestoreFrame.asImageBitmap(),
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
            switching = switching,
            initialLoading = initialLoading,
            restoring = restoring,
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
            onDoubleTapFeedback = doubleTapFeedback::onDoubleTap,            onSeekPreview = {
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
        doubleTapFeedback.ripples.forEach { ripple ->
            key(ripple.id) {
                DoubleTapEdgeRipple(
                    ripple = ripple,
                    onFinished = { doubleTapFeedback.removeRipple(ripple.id) },
                )
            }
        }
        doubleTapFeedback.left?.let { hint ->
            DoubleTapHint(
                side = DoubleTapSide.Left,
                accumulatedMillis = hint.accumulatedMillis,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = maxWidth * 0.06f),
            )
        }
        doubleTapFeedback.right?.let { hint ->
            DoubleTapHint(
                side = DoubleTapSide.Right,
                accumulatedMillis = hint.accumulatedMillis,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = maxWidth * 0.06f),
            )
        }
        // 锁定态只响应点画面：重新显示解锁按钮，不触发任何手势。
        // 加载期间动作按钮仍由 actionAllowed 禁用；点按只用于解锁，始终放行。
        if (locked) {
            val lockedHint = stringResource(R.string.player_screen_locked_hint)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .noRippleClickable({ lock.tapScreen() })
                    .semantics { contentDescription = lockedHint },
            )
        }
        val barsVisible = inputAllowed(PlayerInput.ControlBar, locked) &&
            backEntryVisible(controlsVisible, initialLoading) &&
            panels.controlBarsVisible
        val canGoPrevious = currentIndex > 0
        val canGoNext = currentIndex in 0 until playlist.lastIndex
        // 锁定、自动隐藏或抽屉打开时控制层淡出；淡出期间不再接收触摸。
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(tween(CONTROLS_FADE_MILLIS.toInt())),
            exit = fadeOut(tween(CONTROLS_FADE_MILLIS.toInt())),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            PlayerTopBar(
                title = playlist.getOrNull(currentIndex)?.name.orEmpty(),
                moreVisible = moreVisible,
                interactive = barsVisible,
                rotateEnabled = actionAllowed(PlayerAction.Rotate, switching, initialLoading, restoring),
                rotateAppearanceEnabled = actionLooksEnabled(
                    PlayerAction.Rotate,
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    switching = switching,
                    initialLoading = initialLoading,
                    restoring = restoring,
                ),
                moreEnabled = actionAllowed(PlayerAction.More, switching, initialLoading, restoring),
                moreAppearanceEnabled = actionLooksEnabled(
                    PlayerAction.More,
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    switching = switching,
                    initialLoading = initialLoading,
                    restoring = restoring,
                ),
                onBack = onClose,
                onRotate = {
                    controls.onInteraction()
                    onToggleRotation()
                },
                onToggleMore = {
                    controls.onInteraction()
                    panels.toggle(PlayerPanel.More)
                },
            )
        }
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(tween(CONTROLS_FADE_MILLIS.toInt())),
            exit = fadeOut(tween(CONTROLS_FADE_MILLIS.toInt())),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Controls(
                state = state,
                progress = progress,
                seekPreviewMillis = seekPreviewMillis,
                playlist = playlist,
                currentIndex = currentIndex,
                subtitleTracks = subtitleTracks,
                externalSubtitles = externalSubtitles,
                subtitleSelection = subtitleSelection,
                subtitleMenuVisible = subtitleMenuVisible,
                switching = switching,
                initialLoading = initialLoading,
                restoring = restoring,
                interactive = barsVisible,
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
                onTogglePlaylist = {
                    controls.onInteraction()
                    panels.toggle(PlayerPanel.Playlist)
                },
                onToggleSubtitleMenu = {
                    controls.onInteraction()
                    panels.toggle(PlayerPanel.Subtitles)
                },
                onLock = { lock.tapLockButton() },
                onSelectSubtitle = onSelectSubtitle,
            )
        }
        if (moreVisible && barsVisible) {
            // 更多面板打开时覆盖整屏：点面板外任意区域关闭，面板自身仍可操作。
            val closeHint = stringResource(R.string.player_more_close)
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .noRippleClickable { panels.dismissOutside() }
                        .semantics {
                            contentDescription = closeHint
                            role = Role.Button
                        },
                )
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
                        panels.close()
                    },
                    onCancelSleepTimer = {
                        onCancelSleepTimer()
                        panels.close()
                    },
                    panelWidth = morePanelWidth,
                    panelMaxHeight = morePanelMaxHeight,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(top = PlayerTopBarHeight, end = AppSpacing.Sm),
                )
            }
        }
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
        // 左半屏是关闭抽屉的点击区；抽屉从右侧滑出，只盖住右半屏。
        if (playlistVisible) {
            val closeHint = stringResource(R.string.player_playlist_close)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (playlistDismissTap(offset.x, size.width.toFloat())) panels.close()
                        }
                    }
                    .semantics { contentDescription = closeHint },
            )
        }
        AnimatedVisibility(
            visible = playlistVisible,
            enter = slideInHorizontally(animationSpec = tween(260)) { it },
            exit = slideOutHorizontally(animationSpec = tween(260)) { it },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(maxWidth / 2),
        ) {
            PlaylistDrawer(
                playlist = playlist,
                currentIndex = currentIndex,
                onSelect = { media -> panels.selectPlaylistItem(media, onPlaylistSelect) },
            )
        }
    }
}

/** 手势层：单击显隐控制条、三分区双击、两侧上下拖调音量、中间左右拖跳转。 */
@Composable
private fun PlayerGestureLayer(
    locked: Boolean,
    panelOpen: Boolean,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    levelControls: PlayerLevelControls,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Long,
    onDoubleTapFeedback: (DoubleTapSide, Long) -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onToggleControls: () -> Unit,
    onUserInteraction: () -> Unit,
    onVolumeHint: (Float?) -> Unit,
    onSeekHint: (SeekHint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = gesturesAllowed(locked, panelOpen)
    val gesturesEnabled = enabled
    // 各手势按实际动作单独判定，避免音量/进度被禁时连双击播放/暂停一起关掉。
    val playPauseAllowed = actionAllowed(PlayerAction.PlayPause, switching, initialLoading, restoring)
    val seekAllowed = actionAllowed(PlayerAction.Seek, switching, initialLoading, restoring)
    val volumeAllowed = actionAllowed(PlayerAction.Volume, switching, initialLoading, restoring)
    val doubleTapFeedbackEnabled = doubleTapFeedbackAllowed(
        locked = locked,
        panelOpen = panelOpen,
        switching = switching,
        initialLoading = initialLoading,
        restoring = restoring,
    )
    // 回调与播放位置取最新一份，避免手势层在位置变化时重启。
    val latestOnTogglePlayPause = rememberUpdatedState(onTogglePlayPause)
    val latestOnSeekBy = rememberUpdatedState(onSeekBy)
    val latestOnDoubleTapFeedback = rememberUpdatedState(onDoubleTapFeedback)
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
                    is DoubleTapAction.Seek -> if (doubleTapFeedbackEnabled) {
                        // 用夹取后的实际位移累计，时长未知、到边界或片尾不叠加。
                        val actualDelta = latestOnSeekBy.value(doubleTap.deltaMillis)
                        if (actualDelta != 0L) {
                            latestOnDoubleTapFeedback.value(doubleTap.side, actualDelta)
                        }
                    }

                    DoubleTapAction.TogglePlayPause -> if (playPauseAllowed) {
                        latestOnTogglePlayPause.value()
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .pointerInput(gesturesEnabled, playPauseAllowed, seekAllowed) {
                tapGesture.cancel()
                if (!gesturesEnabled) return@pointerInput
                detectPlayerTaps(tapGesture, dispatchTap)
            }
            .pointerInput(gesturesEnabled, seekAllowed, volumeAllowed) {
                if (!gesturesEnabled) return@pointerInput
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
                                DragGesture.Volume -> if (volumeAllowed && update.volumeDragY != 0f) {
                                    latestOnVolumeHint.value(
                                        levelControls.dragVolume(update.volumeDragY, size.height),
                                    )
                                }

                                DragGesture.Seek -> if (seekAllowed) {
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
                        if (seekAllowed && drag.gesture == DragGesture.Seek && seekDrag?.finish() != null) {
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

/** 拖动进度时的目标时间与增减量提示：居中两行、同字号白字、无底板。 */
@Composable
private fun SeekHintLabel(hint: SeekHint, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.titleMedium.copy(
        color = Color.White,
        fontSize = 34.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.9f),
            offset = Offset(0f, 2f),
            blurRadius = 14f,
        ),
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = formatDuration(hint.targetMillis), style = style, maxLines = 1)
        Text(
            text = signedDuration(hint.deltaMillis),
            style = style,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 双击快退/快进提示：箭头在上，3 秒内累计秒数在下。 */
@Composable
private fun DoubleTapHint(
    side: DoubleTapSide,
    accumulatedMillis: Long,
    modifier: Modifier = Modifier,
) {
    val shadow = Shadow(
        color = Color.Black.copy(alpha = 0.9f),
        offset = Offset(0f, 2f),
        blurRadius = 14f,
    )
    val arrowStyle = MaterialTheme.typography.titleMedium.copy(
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Medium,
        shadow = shadow,
    )
    val secondsStyle = MaterialTheme.typography.titleMedium.copy(
        color = Color.White,
        fontSize = 13.sp,
        fontFeatureSettings = "tnum",
        shadow = shadow,
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (side == DoubleTapSide.Left) "◀◀" else "▶▶",
            style = arrowStyle,
            maxLines = 1,
        )
        Text(
            text = stringResource(
                R.string.player_double_tap_seconds,
                (accumulatedMillis / 1_000L).toInt(),
            ),
            style = secondsStyle,
            maxLines = 1,
        )
    }
}

/** 每次双击从对应屏幕边缘向内扩散的黑色半透明涟漪。 */
@Composable
private fun DoubleTapEdgeRipple(ripple: DoubleTapRipple, onFinished: () -> Unit) {
    val progress = remember(ripple.id) { Animatable(0f) }
    LaunchedEffect(ripple.id) {
        progress.animateTo(1f, tween(durationMillis = 580, easing = LinearOutSlowInEasing))
        onFinished()
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = 0.35f + (1.35f - 0.35f) * progress.value
        val alpha = (1f - progress.value).coerceIn(0f, 1f)
        val radius = max(size.width * 0.46f, 44.dp.toPx()) / 2f * scale
        val center = Offset(
            x = if (ripple.side == DoubleTapSide.Left) 0f else size.width,
            y = size.height / 2f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.4f * alpha),
                0.58f to Color.Black.copy(alpha = 0.22f * alpha),
                0.74f to Color.Transparent,
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
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
    interactive: Boolean,
    rotateEnabled: Boolean,
    rotateAppearanceEnabled: Boolean,
    moreEnabled: Boolean,
    moreAppearanceEnabled: Boolean,
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
            IconButton(onClick = onBack, enabled = interactive) {
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
            IconButton(onClick = onRotate, enabled = interactive && rotateEnabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_screen_rotation),
                    contentDescription = stringResource(R.string.player_rotate),
                    tint = if (rotateAppearanceEnabled) AppColors.Foreground else DisabledToolColor,
                )
            }
            IconButton(
                onClick = onToggleMore,
                enabled = interactive && moreEnabled,
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.player_more),
                    tint = if (moreAppearanceEnabled) playerToolColor(moreVisible) else DisabledToolColor,
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
    subtitleMenuVisible: Boolean,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
    interactive: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
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
        if (subtitleMenuVisible) {
            SubtitlePanel(
                menu = subtitleMenuItems(subtitleTracks, externalSubtitles, subtitleSelection),
                onSelect = onSelectSubtitle,
                interactive = interactive,
                modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            )
        }
        SeekBar(
            mediaKey = playlist.getOrNull(currentIndex)?.location.orEmpty(),
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            previewMillis = seekPreviewMillis,
            enabled = interactive && actionAllowed(PlayerAction.Seek, switching, initialLoading, restoring),
            appearanceEnabled = !initialLoading,
            onPreview = onSeekPreview,
            onCommit = onSeekCommit,
            modifier = Modifier.padding(horizontal = PlayerSeekHorizontalInset),
        )
        PlayerIconBar(
            playing = state == PlaybackState.Playing,
            canGoPrevious = currentIndex > 0,
            canGoNext = currentIndex in 0 until playlist.lastIndex,
            switching = switching,
            initialLoading = initialLoading,
            restoring = restoring,
            interactive = interactive,
            subtitleMenuVisible = subtitleMenuVisible,
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
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
    interactive: Boolean,
    subtitleMenuVisible: Boolean,
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
                enabled = interactive && actionAllowed(PlayerAction.Lock, switching, initialLoading, restoring),
                appearanceEnabled = actionLooksEnabled(
                    PlayerAction.Lock,
                    canGoPrevious = canGoPrevious,
                    canGoNext = canGoNext,
                    switching = switching,
                    initialLoading = initialLoading,
                    restoring = restoring,
                ),
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
                    enabled = interactive && canGoPrevious &&
                        actionAllowed(PlayerAction.Previous, switching, initialLoading, restoring),
                    appearanceEnabled = actionLooksEnabled(
                        PlayerAction.Previous,
                        canGoPrevious = canGoPrevious,
                        canGoNext = canGoNext,
                        switching = switching,
                        initialLoading = initialLoading,
                        restoring = restoring,
                    ),
                    modifier = Modifier.size(metrics.previousNextSize),
                    iconSize = PlayerTransportIconSize,
                )
                PlayerIconButton(
                    icon = if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                    contentDescription = stringResource(
                        if (playing) R.string.player_pause else R.string.player_play,
                    ),
                    onClick = onTogglePlayPause,
                    enabled = interactive &&
                        actionAllowed(PlayerAction.PlayPause, switching, initialLoading, restoring),
                    appearanceEnabled = actionLooksEnabled(
                        PlayerAction.PlayPause,
                        canGoPrevious = canGoPrevious,
                        canGoNext = canGoNext,
                        switching = switching,
                        initialLoading = initialLoading,
                        restoring = restoring,
                    ),
                    modifier = Modifier.size(metrics.playPauseSize),
                    iconSize = PlayerPlayPauseIconSize,
                )
                PlayerIconButton(
                    icon = R.drawable.ic_player_next,
                    contentDescription = stringResource(R.string.player_next),
                    onClick = onNext,
                    enabled = interactive && canGoNext &&
                        actionAllowed(PlayerAction.Next, switching, initialLoading, restoring),
                    appearanceEnabled = actionLooksEnabled(
                        PlayerAction.Next,
                        canGoPrevious = canGoPrevious,
                        canGoNext = canGoNext,
                        switching = switching,
                        initialLoading = initialLoading,
                        restoring = restoring,
                    ),
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
                    enabled = interactive &&
                        actionAllowed(PlayerAction.Subtitles, switching, initialLoading, restoring),
                    appearanceEnabled = actionLooksEnabled(
                        PlayerAction.Subtitles,
                        canGoPrevious = canGoPrevious,
                        canGoNext = canGoNext,
                        switching = switching,
                        initialLoading = initialLoading,
                        restoring = restoring,
                    ),
                    active = subtitleMenuVisible,
                    modifier = Modifier.size(metrics.sideButtonSize),
                )
            }
            val playlistButton: @Composable () -> Unit = {
                PlayerIconButton(
                    icon = R.drawable.ic_player_playlist,
                    contentDescription = stringResource(R.string.player_playlist),
                    onClick = onTogglePlaylist,
                    enabled = interactive &&
                        actionAllowed(PlayerAction.Playlist, switching, initialLoading, restoring),
                    appearanceEnabled = actionLooksEnabled(
                        PlayerAction.Playlist,
                        canGoPrevious = canGoPrevious,
                        canGoNext = canGoNext,
                        switching = switching,
                        initialLoading = initialLoading,
                        restoring = restoring,
                    ),
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

/** 禁用控件的画布色。 */
private val DisabledToolColor = AppColors.Faint.copy(alpha = 0.45f)

/** 播放器图标按钮：只有图标；外观是否禁用与点击是否可用分开，展开时改变颜色。 */
@Composable
private fun PlayerIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    appearanceEnabled: Boolean = true,
    active: Boolean = false,
    iconSize: Dp = PlayerSideIconSize,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = when {
                !appearanceEnabled -> DisabledToolColor
                active -> AppColors.Accent
                else -> AppColors.Foreground
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 进度条：左侧当前时间、右侧总时长；整行可拖动，拖动时左侧时间跟随预览，松手才提交。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    mediaKey: String,
    positionMillis: Long,
    durationMillis: Long,
    previewMillis: Long?,
    enabled: Boolean = true,
    appearanceEnabled: Boolean = true,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maximum = durationMillis.coerceAtLeast(1L)
    // 预览状态绑定到当前媒体与时长：切集后不沿用上一集的 seek 目标。
    var dragMillis by remember(mediaKey, durationMillis) { mutableStateOf<Long?>(null) }
    var heldMillis by remember(mediaKey, durationMillis) { mutableStateOf<Long?>(null) }
    var heldBaseMillis by remember(mediaKey, durationMillis) { mutableStateOf<Long?>(null) }
    var pendingSliderMillis by remember(mediaKey) { mutableStateOf<Long?>(null) }
    var leftTimeWidthPx by remember { mutableStateOf(0f) }
    var rightTimeWidthPx by remember { mutableStateOf(0f) }
    var dragTimeWidthPx by remember(mediaKey, durationMillis) { mutableStateOf<Float?>(null) }
    val previewForDisplay = dragMillis ?: previewMillis ?: heldMillis
    val progress = seekBarProgressMillis(positionMillis, durationMillis, previewForDisplay)
    val unknownText = stringResource(R.string.player_time_unknown)
    val labels = seekBarLabels(positionMillis, durationMillis, previewForDisplay, unknownText)
    val sliderEnabled = enabled && labels.seekEnabled
    // 松手后保留末次预览，等播放位置追平再撤掉，避免左侧时间回跳。
    LaunchedEffect(positionMillis, heldMillis, heldBaseMillis) {
        val held = heldMillis ?: return@LaunchedEffect
        if (!shouldKeepSeekPreview(positionMillis, held, heldBaseMillis, false, false)) {
            heldMillis = null
        }
    }
    LaunchedEffect(heldMillis) {
        val held = heldMillis ?: return@LaunchedEffect
        delay(SEEK_PREVIEW_HOLD_MILLIS)
        if (!shouldKeepSeekPreview(positionMillis, held, heldBaseMillis, false, true)) {
            heldMillis = null
        }
    }
    val previewAt: (Long) -> Unit = {
        heldMillis = null
        heldBaseMillis = null
        onPreview(it)
    }
    val commitAt: (Long, Long) -> Unit = { target, base ->
        heldMillis = target
        heldBaseMillis = base
        onCommit()
    }
    val latestPreviewAt = rememberUpdatedState(previewAt)
    val latestCommitAt = rememberUpdatedState(commitAt)
    val latestPositionMillis = rememberUpdatedState(positionMillis)
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.onPrimary,
        activeTrackColor = AppColors.Accent,
        inactiveTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledThumbColor = DisabledToolColor,
        disabledActiveTrackColor = DisabledToolColor,
        disabledInactiveTrackColor = DisabledToolColor,
    )
    val thumbColor = if (appearanceEnabled && labels.seekEnabled) colors.thumbColor else colors.disabledThumbColor
    val activeTrackColor = if (appearanceEnabled && labels.seekEnabled) {
        colors.activeTrackColor
    } else {
        colors.disabledActiveTrackColor
    }
    val inactiveTrackColor = if (appearanceEnabled && labels.seekEnabled) {
        colors.inactiveTrackColor
    } else {
        colors.disabledInactiveTrackColor
    }
    val stateText = stringResource(R.string.player_progress_state, labels.current, labels.total)
    val timeStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    // 切集只拦截拖动，颜色保持可用；首次打开仍按不可用外观置灰。
    val timeColor = if (appearanceEnabled) AppColors.Foreground else DisabledToolColor
    val latestLeftTimeWidthPx = rememberUpdatedState(leftTimeWidthPx)
    val latestRightTimeWidthPx = rememberUpdatedState(rightTimeWidthPx)
    val density = LocalDensity.current
    val latestSpacingPx = rememberUpdatedState(with(density) { PlayerSeekTimeGap.toPx() })
    val latestThumbRadiusPx = rememberUpdatedState(with(density) { PlayerSeekThumbSize.toPx() / 2f })
    val frozenTimeWidth = dragTimeWidthPx?.let { with(density) { it.toDp() } }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlayerSeekTouchHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PlayerSeekTimeGap),
        ) {
            Text(
                text = labels.current,
                style = timeStyle,
                color = timeColor,
                maxLines = 1,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .then(if (frozenTimeWidth != null) Modifier.width(frozenTimeWidth) else Modifier)
                    .onSizeChanged { leftTimeWidthPx = it.width.toFloat() },
            )
            Slider(
                value = progress.toFloat(),
                onValueChange = {
                    val target = it.toLong()
                    pendingSliderMillis = target
                    previewAt(target)
                },
                onValueChangeFinished = {
                    val target = pendingSliderMillis
                    pendingSliderMillis = null
                    if (target != null) {
                        commitAt(target, latestPositionMillis.value)
                    } else {
                        onCommit()
                    }
                },
                enabled = sliderEnabled,
                valueRange = 0f..maximum.toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .height(PlayerSeekTouchHeight)
                    .semantics { stateDescription = stateText },
                colors = colors,
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(PlayerSeekThumbSize)
                            .background(thumbColor, CircleShape),
                    )
                },
                track = {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PlayerSeekTrackHeight),
                    ) {
                        val centerY = size.height / 2f
                        drawLine(
                            color = inactiveTrackColor,
                            start = Offset(0f, centerY),
                            end = Offset(size.width, centerY),
                            strokeWidth = size.height,
                            cap = StrokeCap.Round,
                        )
                        if (progress > 0L) {
                            drawLine(
                                color = activeTrackColor,
                                start = Offset(0f, centerY),
                                end = Offset(
                                    size.width * progress.toFloat() / maximum.toFloat(),
                                    centerY,
                                ),
                                strokeWidth = size.height,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                },
            )
            Text(
                text = labels.total,
                style = timeStyle,
                color = timeColor,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .then(if (frozenTimeWidth != null) Modifier.width(frozenTimeWidth) else Modifier)
                    .onSizeChanged { rightTimeWidthPx = it.width.toFloat() },
            )
        }
        if (sliderEnabled) {
            // 覆盖整行接收触摸：两侧时间区与间隙也能开始拖动；Slider 保留无障碍语义。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // 时间标签跨位数会改变宽度；不把它放进 key，避免拖动中途重启手势。
                    .pointerInput(sliderEnabled, maximum) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val downLeftTimeWidthPx = latestLeftTimeWidthPx.value
                            val downRightTimeWidthPx = latestRightTimeWidthPx.value
                            // 首帧按冻结前的轨道映射，避免左右标签不等时按下瞬间跳位。
                            var target = seekBarDownMillis(
                                x = down.position.x,
                                widthPx = size.width.toFloat(),
                                leftTimeWidthPx = downLeftTimeWidthPx,
                                rightTimeWidthPx = downRightTimeWidthPx,
                                spacingPx = latestSpacingPx.value,
                                thumbRadiusPx = latestThumbRadiusPx.value,
                                durationMillis = maximum,
                            ) ?: return@awaitEachGesture
                            val frozenTimeWidthPx = seekBarDragTimeWidth(
                                leftTimeWidthPx = downLeftTimeWidthPx,
                                rightTimeWidthPx = downRightTimeWidthPx,
                            )
                            if (frozenTimeWidthPx > 0f) dragTimeWidthPx = frozenTimeWidthPx
                            val bounds = seekBarTrackBounds(
                                widthPx = size.width.toFloat(),
                                leftTimeWidthPx = if (frozenTimeWidthPx > 0f) {
                                    frozenTimeWidthPx
                                } else {
                                    downLeftTimeWidthPx
                                },
                                rightTimeWidthPx = if (frozenTimeWidthPx > 0f) {
                                    frozenTimeWidthPx
                                } else {
                                    downRightTimeWidthPx
                                },
                                spacingPx = latestSpacingPx.value,
                                thumbRadiusPx = latestThumbRadiusPx.value,
                            )
                            val seekBase = latestPositionMillis.value
                            down.consume()
                            dragMillis = target
                            latestPreviewAt.value(target)
                            var completed = false
                            var cancelled = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                    val pressedIds = event.changes
                                        .filter { it.pressed }
                                        .map { it.id.value }
                                    if (isMultiPointerDrag(down.id.value, pressedIds)) {
                                        cancelled = true
                                        break
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        completed = true
                                        break
                                    }
                                    seekBarMillisAt(
                                        x = change.position.x,
                                        trackStart = bounds.start,
                                        trackEnd = bounds.end,
                                        durationMillis = maximum,
                                    )?.let { updated ->
                                        if (updated != target) {
                                            target = updated
                                            dragMillis = target
                                            latestPreviewAt.value(target)
                                        }
                                    }
                                }
                            } finally {
                                dragMillis = null
                                dragTimeWidthPx = null
                            }
                            if (completed) {
                                latestCommitAt.value(target, seekBase)
                            } else if (cancelled) {
                                latestPreviewAt.value(latestPositionMillis.value)
                            }
                        }
                    },
            )
        }
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

/** 播放列表抽屉：占右半屏，右侧加深的低透明度黑底，当前项居中。 */
@Composable
private fun PlaylistDrawer(
    playlist: List<MediaFile>,
    currentIndex: Int,
    onSelect: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerPlaylistGradient)
            // 抽屉贴在末端屏边，只避让上、下、末端 inset；条目左右各留 16dp，左侧不叠加系统 inset。
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Vertical + WindowInsetsSides.End,
                ),
            )
            .blockPlayerGestures(),
    ) {
        Text(
            text = stringResource(R.string.player_playlist_count, playlist.size),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            modifier = Modifier.padding(
                start = AppSpacing.Lg,
                end = AppSpacing.Lg,
                top = AppSpacing.Md,
                bottom = AppSpacing.Xs,
            ),
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val density = LocalDensity.current
            val viewportPx = with(density) { maxHeight.roundToPx() }
            val itemPx = with(density) { PlayerPlaylistItemHeight.roundToPx() }
            // 上下各留半个视口，当前项才能滚到中间；末尾由可滚动范围夹紧。
            val edgePaddingPx = ((viewportPx - itemPx) / 2f).roundToInt().coerceAtLeast(0)
            val verticalPadding = with(density) { edgePaddingPx.toDp() }
            LaunchedEffect(currentIndex, playlist.size, viewportPx, itemPx) {
                if (playlist.isNotEmpty()) {
                    val targetOffset = playlistCenterScrollOffset(
                        index = currentIndex,
                        itemCount = playlist.size,
                        viewportPx = viewportPx,
                        itemPx = itemPx,
                    )
                    listState.scrollToItem(0)
                    listState.scrollBy(targetOffset.toFloat())
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = verticalPadding,
                    bottom = verticalPadding,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(playlist) { index, media ->
                    val active = index == currentIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PlayerPlaylistHorizontalInset)
                            .height(PlayerPlaylistItemHeight)
                            .background(
                                color = if (active) AppColors.Accent.copy(alpha = 0.16f) else Color.Transparent,
                                shape = RoundedCornerShape(AppRadius.Sm),
                            )
                            .noRippleClickable { onSelect(media) },
                    ) {
                        Text(
                            text = playlistItemIndexLabel(index),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = Color.White,
                            maxLines = 1,
                        )
                        Text(
                            text = media.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = PlayerPlaylistIndexGap),
                        )
                        if (active) {
                            Text(
                                text = "▶",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Accent,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(start = AppSpacing.Sm)
                                    .width(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 字幕菜单：内嵌轨道、可匹配外挂、关闭，当前生效项用强调色。 */
@Composable
private fun SubtitlePanel(
    menu: List<SubtitleMenuItem>,
    onSelect: (SubtitleMenuItem) -> Unit,
    interactive: Boolean,
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
                        .noRippleClickable({ onSelect(item) }, enabled = interactive)
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
