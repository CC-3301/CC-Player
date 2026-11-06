package com.cc3301.ccplayer.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
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
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.cc3301.ccplayer.playback.PlaybackController
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.SeekSettlement
import com.cc3301.ccplayer.playback.SleepTimerOption
import com.cc3301.ccplayer.playback.SleepTimerState
import com.cc3301.ccplayer.playback.SubtitleSelection
import com.cc3301.ccplayer.playback.SubtitleTrack
import com.cc3301.ccplayer.playback.SurfacePresentation
import com.cc3301.ccplayer.playback.customSleepMinutes
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.ui.theme.AppColors
import com.cc3301.ccplayer.ui.theme.AppRadius
import com.cc3301.ccplayer.ui.theme.AppSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * 面板打开时的栏内空白区：点击交给关闭层，不吞掉、也不产生播放动作。
 * 只接未被按钮热区消费的按下，按钮自己的点击照常生效。
 */
@Composable
private fun Modifier.tapToDismiss(onTap: () -> Unit): Modifier {
    val latestOnTap = rememberUpdatedState(onTap)
    return pointerInput(Unit) {
        detectTapGestures { latestOnTap.value() }
    }
}

/**
 * 控件按住期间不自动隐藏：按下即通知，抬起或手势取消时通知恢复。
 * 只在 Initial 阶段观察且不消费事件，点击与拖动照常传给子节点。
 */
private fun Modifier.holdControlsVisible(onPressChange: (Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onPressChange(true)
            try {
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
            } finally {
                onPressChange(false)
            }
        }
    }

/** 鼠标移动或进入时的指针位置；触摸与手写笔不参与指针显隐，返回 null。 */
private fun PointerEvent.mousePosition(): Offset? {
    if (type != PointerEventType.Enter && type != PointerEventType.Move) return null
    return changes.firstOrNull { it.type == PointerType.Mouse }?.position
}

/** 播放页点击：按压反馈由目标区域决定，覆盖范围外不显示涟漪。 */
@Composable
private fun Modifier.playerPressClickable(
    target: PlayerPressTarget,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = playerPressIndication(target),
    onClick = onClick,
)

/** 播放器浮层统一面板：半透明深色底、细边框与统一圆角；裁剪子内容与涟漪。 */
private fun Modifier.playerPanel(): Modifier = this
    .clip(PlayerPanelShape)
    .background(PlayerPanelColor, PlayerPanelShape)
    .border(1.dp, AppColors.Line, PlayerPanelShape)

/** 工具按钮颜色：面板展开或定时已设置时用强调色，其余用弱化文字色。 */
private fun playerToolColor(active: Boolean): Color =
    if (active) AppColors.Accent else AppColors.Muted

/** 播放器次级文字按钮：统一字号、内边距与禁用态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = AppColors.Foreground,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    CompositionLocalProvider(
        LocalRippleConfiguration provides PlayerPressRippleConfiguration,
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
}

/** 缓冲指示：转圈与“正在缓冲”，首次打开叠在黑屏上，切集叠在屏幕上停住的旧画面上。 */
@Composable
private fun BufferingIndicator(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            color = PlayerBufferingSpinnerColor,
            strokeWidth = PlayerBufferingSpinnerStrokeWidth,
            modifier = Modifier.size(PlayerBufferingSpinnerSize),
        )
        val text = stringResource(R.string.player_buffering)
        val style = MaterialTheme.typography.bodyMedium
        // Stroke 以字形中心为轴，内侧一半会被填充层盖住；按两倍宽度绘制，露在外侧的可见描边等于常量宽度。
        val strokeWidth = with(LocalDensity.current) { (PlayerBufferingTextStrokeWidth * 2f).toPx() }
        Box(modifier = Modifier.padding(top = AppSpacing.Sm)) {
            // 先画描边层，再画填充层。
            Text(
                text = text,
                style = style.copy(drawStyle = Stroke(width = strokeWidth)),
                color = PlayerBufferingTextScrimColor.copy(alpha = PLAYER_BUFFERING_TEXT_SCRIM_ALPHA),
                modifier = Modifier.clearAndSetSemantics {},
            )
            Text(
                text = text,
                style = style,
                color = AppColors.Foreground,
            )
        }
    }
}

@Composable
fun PlayerScreen(
    state: PlaybackState,
    progress: PlaybackProgress,
    seekPreviewMillis: Long?,
    seekSettlement: SeekSettlement?,
    playlist: List<MediaFile>,
    currentIndex: Int,
    decodeMode: DecodeMode,
    loopMode: LoopMode,
    orientationMode: OrientationMode,
    orientationOverride: ScreenOrientation?,
    screenOrientation: ScreenOrientation,
    autoPlayNext: Boolean,
    sleepTimerState: SleepTimerState,
    sleepTimerOption: SleepTimerOption?,
    levelControls: PlayerLevelControls,
    surfaceCallback: MpvSurfaceView.Callback,
    surfacePresentation: SurfacePresentation,
    switching: Boolean,
    restoring: Boolean,
    buffering: Boolean,
    initialLoading: Boolean,
    subtitleTracks: List<SubtitleTrack>,
    externalSubtitles: List<MediaFile>,
    subtitleSelection: SubtitleSelection,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long, Boolean) -> Unit,
    onSeekCommit: () -> Unit,
    onSeekBy: (Long) -> Long,
    onSeekStep: (Long) -> Long?,
    onSeekHeartbeat: () -> Unit,
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
    // 控制层在场（可见或淡出中）：指针、退场可点与面板清理都按这个口径，第 5 秒才算隐藏。
    val controlsPresent = !controls.hidden
    // 本次播放开始过切集之后，加载阶段不再算首次缓冲。
    var switchedThisSession by remember { mutableStateOf(false) }
    LaunchedEffect(switching) {
        switchedThisSession = switchedSinceOpen(switching, switchedThisSession)
    }
    val firstBuffering = isFirstBuffering(initialLoading, switchedThisSession)
    // 首次撤黑门：进页盖着黑等屏幕转过来，方向对齐或等满时限后再撤黑。
    // 比例未知期间一直盖黑由比例判据负责，不进这道门，也不设超时。
    var placementGate by remember { mutableStateOf(PlayerPlacementGate()) }
    val ratioKnown = hasKnownVideoAspectRatio(surfacePresentation.aspectRatio)
    val rotationPending = awaitsVideoRotation(
        mode = orientationMode,
        videoAspectRatio = surfacePresentation.aspectRatio,
        screenOrientation = screenOrientation,
        rotationOverride = orientationOverride,
    )
    val awaitsOrientation = placementGate.awaitsOrientation(
        ratioKnown = ratioKnown,
        showsPicture = surfacePresentation.showsPicture,
        rotationPending = rotationPending,
    )
    // 每帧把事实喂给状态机：开始时进入等待，方向对齐即结案；结案后方向回摆不再重开计时、不再重新盖黑。
    SideEffect {
        placementGate = placementGate.withFrame(
            ratioKnown = ratioKnown,
            showsPicture = surfacePresentation.showsPicture,
            rotationPending = rotationPending,
        )
    }
    LaunchedEffect(awaitsOrientation) {
        if (!awaitsOrientation) return@LaunchedEffect
        runOrientationWait()
        placementGate = placementGate.withWaitLimit()
    }
    // 顶/底栏的动画目标与在场：动画目标只看完全可见，在场含淡出中。
    val bars = playerBarsPresence(
        locked = locked,
        phase = controls.phase,
        panelBarsVisible = panels.controlBarsVisible,
        firstBuffering = firstBuffering,
    )
    val barsShown = bars.shown
    val barsPresent = bars.present
    // 面板可见性统一由此判定；不可见的面板不能拦截手势，打开期间切集不关闭面板。
    val panelVisible = panelVisible(
        anyOpen = panels.anyOpen,
        controlsVisible = controlsVisible,
        locked = locked,
    )
    val playlistVisible = panels.playlistOpen
    val subtitleMenuVisible = panels.subtitlesOpen
    val moreVisible = panels.moreOpen

    // 面板或抽屉打开时，系统返回只关闭面板，不离开播放页。
    BackHandler(enabled = playerBackClosesPanel(panels.anyOpen)) { panels.close() }

    // 首次加载与切集不改变控制层显隐：显示状态重新计时，隐藏状态全程保持隐藏。
    // 已打开的面板保持打开，内容按不可用外观处理。
    LaunchedEffect(switching, initialLoading) {
        controls.onLoadingPhaseChanged()
    }

    // 自动隐藏计时仍以“有面板打开”为准，与可见性判定分开。
    LaunchedEffect(panels.anyOpen) {
        controls.updatePanelOpen(panels.anyOpen)
    }

    // 控制层隐藏或锁定时清理面板状态，避免面板不渲染却继续拦截手势。
    LaunchedEffect(controlsVisible, locked) {
        panels.closeWhenControlsUnavailable(controlsVisible, locked)
    }
    // 控制层离开后涟漪不再渲染：清掉未播完的条目，重新显示时不重播。
    LaunchedEffect(controlsPresent) {
        doubleTapFeedback.syncRipples(controlsPresent)
    }
    LaunchedEffect(
        controls.phase,
        controls.panelOpen,
        controls.pressing,
        controls.lastInteractionAtMillis,
        controls.fadeOutStartedAtMillis,
        switching,
        initialLoading,
    ) {
        // 加载全程不自动隐藏，避免切集期间控制条消失。
        runControlsAutoHide(
            controls = controls,
            holdVisible = { switching || initialLoading },
            delayMillis = { delay(it) },
        )
    }

    LaunchedEffect(
        lock.locked,
        lock.unlockVisible,
        lock.unlockFading,
        lock.unlockPressing,
        lock.unlockShownAtMillis,
        lock.unlockFadeOutStartedAtMillis,
    ) {
        runUnlockAutoHide(lock = lock, delayMillis = { delay(it) })
    }

    LaunchedEffect(doubleTapFeedback.left, doubleTapFeedback.right) {
        while (doubleTapFeedback.left != null || doubleTapFeedback.right != null) {
            val remaining = doubleTapFeedback.millisUntilExpire() ?: break
            delay(remaining)
            doubleTapFeedback.expire()
        }
    }

    // 切集、加载、恢复、面板或锁定状态切换时清空手势反馈，避免盖在缓冲层上。
    LaunchedEffect(switching, initialLoading, restoring, panelVisible, locked) {
        if (doubleTapFeedbackBlocked(locked, panelVisible, switching, initialLoading, restoring)) {
            doubleTapFeedback.reset()
            seekHint = null
            volumeHint = null
        }
    }

    // 预览结束（松手、取消、滚轮落定）就收起目标时间提示，提示不晚于预览。
    LaunchedEffect(seekPreviewMillis) {
        if (seekPreviewMillis == null) seekHint = null
    }

    // 指针跟随顶/底栏的实际可见性；栏隐藏时指针一并隐藏，有效移动只唤出指针。其它界面不处理指针。
    val context = LocalContext.current
    val hiddenPointerIcon = remember(context) {
        PointerIcon(android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL))
    }
    val pointer = remember { PlayerPointerVisibility() }
    LaunchedEffect(barsPresent) {
        pointerFollowsBars(barsPresent, pointer)
    }
    // 指针静止满 3 秒隐藏：常驻循环在指针可见时按剩余时间计时，移动与显示都从当前时刻重新起算。
    LaunchedEffect(pointer) {
        runPointerAutoHide(
            pointer = pointer,
            awaitVisible = { snapshotFlow { pointer.visible }.first { it } },
            delayMillis = { delay(it) },
        )
    }

    val pointerDensity = LocalDensity.current
    BoxWithConstraints(modifier = modifier
        .fillMaxSize()
        .background(Color.Black)
        .pointerHoverIcon(
            icon = if (pointer.visible) PointerIcon.Default else hiddenPointerIcon,
            overrideDescendants = true,
        )
        .pointerInput(pointerDensity.density) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val position = event.mousePosition() ?: continue
                    if (event.type == PointerEventType.Enter) {
                        pointer.onPointerEnter()
                    } else {
                        pointer.onPointerMove(position, pointerDensity)
                    }
                }
            }
        }) {
        val density = LocalDensity.current
        // 视频区始终铺满容器，比例与黑边交给 mpv 在 Surface 内处理。
        val viewport = videoViewport(
            containerWidth = with(density) { maxWidth.toPx() },
            containerHeight = with(density) { maxHeight.toPx() },
        )
        val morePanelWidth = (maxWidth - 72.dp).coerceIn(1.dp, 320.dp)
        val morePanelMaxHeight = (maxHeight - PlayerTopBarHeight - PlayerControlBarReserve)
            .coerceAtLeast(72.dp)
        // 抽屉落位随画面宽高比：横屏贴右半屏，竖屏贴下半屏。
        val playlistPlacement = with(density) {
            playlistDrawerPlacement(maxWidth.toPx(), maxHeight.toPx())
        }
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
        // 黑底画在视频视图之后：屏幕上没有可显示的画面时铺黑底（首次打开、片尾恢复、切集无画面、无视频轨）。
        // 刚进播放页、比例还没到时也盖住；横向视频还要等屏幕转到位，别让竖向摆出来的首帧先露一下。
        if (
            showPlayerBlackLayer(
                showsPicture = surfacePresentation.showsPicture,
                videoAspectRatio = surfacePresentation.aspectRatio,
                orientationSettled = placementGate.settled,
                orientationPending = awaitsOrientation,
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        }
        // 黑屏期间不显示转圈与文字：只有后台切集与播放态回前台撤帧这两类黑屏不显示，
        // 其余场景（首次缓冲、片尾恢复、恢复原片、无视频轨）维持原有提示规则。
        if (buffering && !surfacePresentation.quietBlack) {
            BufferingIndicator(modifier = Modifier.align(Alignment.Center))
        }
        PlayerGestureLayer(
            locked = locked,
            panelOpen = panelVisible,
            controlsPresent = controlsPresent,
            switching = switching,
            initialLoading = initialLoading,
            restoring = restoring,
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            levelControls = levelControls,
            onTogglePlayPause = {
                controls.onUserGesture()
                onPlayPause()
            },
            onSeekBy = {
                controls.onUserGesture()
                onSeekBy(it)
            },
            onDoubleTapFeedback = { side, deltaMillis, ripple ->
                doubleTapFeedback.onDoubleTap(side, deltaMillis, ripple)
            },
            onSeekPreview = { position, requestSeek ->
                controls.onUserGesture()
                onSeekPreview(position, requestSeek)
            },
            onSeekCommit = {
                controls.onUserGesture()
                onSeekCommit()
            },
            onSeekStep = onSeekStep,
            onSeekHeartbeat = onSeekHeartbeat,
            onToggleControls = { if (toggleControlsAllowed(firstBuffering)) controls.toggle() },
            onGestureInteraction = { controls.onUserGesture() },
            onGesturePress = { pressed -> controls.onGesturePress(pressed) },
            onVolumeHint = { volumeHint = it },
            onSeekHint = { seekHint = it },
            modifier = Modifier.fillMaxSize(),
        )
        // 手势提示元素由控制层在场状态与手势类型决定：已隐藏时留跳转提示与双击 ±10 秒的该侧箭头与累计秒数。
        val seekHints = shownGestureHints(controlsPresent, GestureHintSource.Seek)
        val volumeHints = shownGestureHints(controlsPresent, GestureHintSource.Volume)
        val doubleTapSeekHints = shownGestureHints(controlsPresent, GestureHintSource.DoubleTapSeek)
        if (GestureHint.VolumePercent in volumeHints) {
            volumeHint?.let { fraction ->
                LevelHintLabel(fraction = fraction, modifier = Modifier.align(Alignment.Center))
            }
        }
        if (GestureHint.SeekTarget in seekHints) {
            seekHint?.let { hint ->
                SeekHintLabel(hint = hint, modifier = Modifier.align(Alignment.Center))
            }
        }
        if (GestureHint.DoubleTapRipple in doubleTapSeekHints) {
            doubleTapFeedback.ripples.forEach { ripple ->
                key(ripple.id) {
                    DoubleTapEdgeRipple(
                        ripple = ripple,
                        onFinished = { doubleTapFeedback.removeRipple(ripple.id) },
                    )
                }
            }
        }
        if (GestureHint.DoubleTapSide in doubleTapSeekHints) {
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
        }
        // 锁定态只响应点画面：重新显示解锁按钮，不触发任何手势。
        // 加载期间动作按钮仍由 actionAllowed 禁用；点按只用于解锁，始终放行。
        if (locked) {
            val lockedHint = stringResource(R.string.player_screen_locked_hint)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerPressClickable(PlayerPressTarget.LockLayer) { lock.tapScreen() }
                    .semantics { contentDescription = lockedHint },
            )
        }
        val canGoPrevious = currentIndex > 0
        val canGoNext = currentIndex in 0 until playlist.lastIndex
        // 面板打开时栏内空白区让给关闭层；按钮热区在自己的点击里消费，不受影响。
        val blankTap: (() -> Unit)? = if (panelVisible) ({ panels.dismissOutside() }) else null
        if (moreVisible && panelVisible) {
            // 更多面板打开时覆盖整屏：点面板外任意区域关闭，面板自身仍可操作。
            // 先于顶栏/底栏组合：栏区画在上面，面板打开时按钮照常可点。
            val closeHint = stringResource(R.string.player_more_close)
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .playerPressClickable(PlayerPressTarget.OutsideDismissLayer) {
                            panels.dismissOutside()
                        }
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
                    enabled = actionAllowed(PlayerAction.More, switching, initialLoading, restoring),
                    panelWidth = morePanelWidth,
                    panelMaxHeight = morePanelMaxHeight,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(top = PlayerTopBarHeight, end = AppSpacing.Sm),
                )
            }
        }
        // 锁定、自动隐藏或抽屉打开时控制层淡出；淡出动画期间控件保持可点击，避免看得见却点不到。
        AnimatedVisibility(
            visible = barsShown,
            enter = fadeIn(tween(CONTROLS_FADE_IN_MILLIS.toInt())),
            exit = fadeOut(tween(if (controls.hiddenImmediately) 0 else CONTROLS_FADE_OUT_MILLIS.toInt())),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            val topBarInteractive = playerBarsInteractive(
                barsVisible = barsShown,
                exitAnimating = transition.targetState == EnterExitState.PostExit,
                locked = locked,
            )
            PlayerTopBar(
                title = playlist.getOrNull(currentIndex)?.name.orEmpty(),
                moreVisible = moreVisible,
                interactive = topBarInteractive,
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
                    controls.showByUserAction()
                    onToggleRotation()
                },
                onToggleMore = {
                    controls.showByUserAction()
                    panels.toggle(PlayerPanel.More)
                },
                onBlankTap = blankTap,
                modifier = Modifier.holdControlsVisible { pressed ->
                    if (pressed) controls.onPressDown() else controls.onPressUp()
                },
            )
        }
        AnimatedVisibility(
            visible = barsShown,
            enter = fadeIn(tween(CONTROLS_FADE_IN_MILLIS.toInt())),
            exit = fadeOut(tween(if (controls.hiddenImmediately) 0 else CONTROLS_FADE_OUT_MILLIS.toInt())),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val bottomBarInteractive = playerBarsInteractive(
                barsVisible = barsShown,
                exitAnimating = transition.targetState == EnterExitState.PostExit,
                locked = locked,
            )
            Controls(
                state = state,
                progress = progress,
                seekPreviewMillis = seekPreviewMillis,
                seekSettlement = seekSettlement,
                playlist = playlist,
                currentIndex = currentIndex,
                subtitleTracks = subtitleTracks,
                externalSubtitles = externalSubtitles,
                subtitleSelection = subtitleSelection,
                subtitleMenuVisible = subtitleMenuVisible && panelVisible,
                switching = switching,
                initialLoading = initialLoading,
                restoring = restoring,
                interactive = bottomBarInteractive,
                onPlayPause = {
                    controls.showByUserAction()
                    onPlayPause()
                },
                onPrevious = {
                    // 上一集每一击都要把切集目标前移，不去抖。
                    controls.showByUserAction()
                    onPrevious()
                },
                onNext = {
                    // 下一集每一击都要把切集目标前移，不去抖。
                    controls.showByUserAction()
                    onNext()
                },
                onSeekPreview = { position, requestSeek ->
                    controls.onUserGesture()
                    onSeekPreview(position, requestSeek)
                },
                onSeekCommit = {
                    controls.onUserGesture()
                    onSeekCommit()
                },
                onSeekHeartbeat = onSeekHeartbeat,
                onTogglePlaylist = {
                    controls.showByUserAction()
                    panels.toggle(PlayerPanel.Playlist)
                },
                onToggleSubtitleMenu = {
                    controls.showByUserAction()
                    panels.toggle(PlayerPanel.Subtitles)
                },
                onLock = { lock.tapLockButton() },
                onSelectSubtitle = onSelectSubtitle,
                onBlankTap = blankTap,
                modifier = Modifier.holdControlsVisible { pressed ->
                    if (pressed) controls.onPressDown() else controls.onPressUp()
                },
            )
        }
        // 解锁按钮随解锁动作立即离开组合；定时隐藏后仍保留组合，重新出现走 200 毫秒淡入。
        if (!lock.unlockDismissed) {
            AnimatedVisibility(
                visible = lock.unlockVisible && !lock.unlockFading,
                enter = fadeIn(tween(CONTROLS_FADE_IN_MILLIS.toInt())),
                exit = fadeOut(tween(CONTROLS_FADE_OUT_MILLIS.toInt())),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                UnlockButton(
                    onTap = {
                        lock.tapLockButton()
                        controls.showByUserAction()
                    },
                    modifier = Modifier
                        .holdControlsVisible { pressed ->
                            if (pressed) lock.onPressDown() else lock.onPressUp()
                        }
                        .safeDrawingPadding()
                        .padding(AppSpacing.Lg),
                )
            }
        }
        // 抽屉之外的那一半是关闭区：横屏点左半屏，竖屏点上半屏。
        if (playlistVisible && panelVisible) {
            val closeHint = stringResource(R.string.player_playlist_close)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (
                                playlistDismissTap(
                                    tapX = offset.x,
                                    tapY = offset.y,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                )
                            ) {
                                panels.close()
                            }
                        }
                    }
                    .semantics { contentDescription = closeHint },
            )
        }
        AnimatedVisibility(
            visible = playlistVisible && panelVisible,
            enter = when (playlistPlacement) {
                PlaylistDrawerPlacement.RightHalf -> slideInHorizontally(animationSpec = tween(260)) { it }
                PlaylistDrawerPlacement.BottomHalf -> slideInVertically(animationSpec = tween(260)) { it }
            },
            exit = when (playlistPlacement) {
                PlaylistDrawerPlacement.RightHalf -> slideOutHorizontally(animationSpec = tween(260)) { it }
                PlaylistDrawerPlacement.BottomHalf -> slideOutVertically(animationSpec = tween(260)) { it }
            },
            modifier = when (playlistPlacement) {
                PlaylistDrawerPlacement.RightHalf -> Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(maxWidth / 2)
                PlaylistDrawerPlacement.BottomHalf -> Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(maxHeight / 2)
            },
        ) {
            PlaylistDrawer(
                playlist = playlist,
                currentIndex = currentIndex,
                placement = playlistPlacement,
                onSelect = { media -> panels.selectPlaylistItem(media, onPlaylistSelect) },
            )
        }
    }
}

/** 拖动期间按心跳周期通知控制层：兜底只看心跳是否超时，不看指针事件频率。 */
@Composable
private fun PreviewHeartbeat(dragging: Boolean, onHeartbeat: () -> Unit) {
    val latestOnHeartbeat = rememberUpdatedState(onHeartbeat)
    LaunchedEffect(dragging) {
        while (dragging) {
            delay(PlaybackController.PREVIEW_HEARTBEAT_MILLIS)
            latestOnHeartbeat.value()
        }
    }
}

/** 手势层：单击显隐控制条、三分区双击、两侧上下拖调音量、中间左右拖跳转、中间滚轮调时长。 */
@Composable
private fun PlayerGestureLayer(
    locked: Boolean,
    panelOpen: Boolean,
    controlsPresent: Boolean,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    levelControls: PlayerLevelControls,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Long,
    onDoubleTapFeedback: (DoubleTapSide, Long, Boolean) -> Unit,
    onSeekPreview: (Long, Boolean) -> Unit,
    onSeekCommit: () -> Unit,
    onSeekStep: (Long) -> Long?,
    onSeekHeartbeat: () -> Unit,
    onToggleControls: () -> Unit,
    onGestureInteraction: () -> Unit,
    onGesturePress: (Boolean) -> Unit,
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
    val latestOnSeekStep = rememberUpdatedState(onSeekStep)
    val latestOnSeekHeartbeat = rememberUpdatedState(onSeekHeartbeat)
    val latestOnToggleControls = rememberUpdatedState(onToggleControls)
    val latestOnGestureInteraction = rememberUpdatedState(onGestureInteraction)
    val latestOnGesturePress = rememberUpdatedState(onGesturePress)
    val latestOnVolumeHint = rememberUpdatedState(onVolumeHint)
    val latestOnSeekHint = rememberUpdatedState(onSeekHint)
    val latestPosition = rememberUpdatedState(positionMillis)
    val latestDuration = rememberUpdatedState(durationMillis)
    // 控制层可能在手势层注册之后才隐藏：读状态而不是闭包里的旧值。
    val latestControlsPresent = rememberUpdatedState(controlsPresent)
    val tapGesture = remember { PlayerTapGesture() }
    val dispatchTap: (TapGestureAction) -> Unit = { action ->
        when (action) {
            TapGestureAction.ToggleControls -> latestOnToggleControls.value()
            is TapGestureAction.DoubleTap -> {
                // 双击只重置自动隐藏计时：隐藏态双击暂停不显示任何元素，双击 ±10 秒只显示箭头与累计秒数。
                latestOnGestureInteraction.value()
                val doubleTap = action.action
                when (doubleTap) {
                    is DoubleTapAction.Seek -> if (doubleTapFeedbackEnabled) {
                        // 该手势此刻显示哪些提示元素：箭头与累计秒数、涟漪都由此判定。
                        val hints = shownGestureHints(latestControlsPresent.value, doubleTapHintSource(doubleTap))
                        // 用夹取后的实际位移累计，时长未知、到边界或片尾不叠加。
                        val actualDelta = latestOnSeekBy.value(doubleTap.deltaMillis)
                        if (actualDelta != 0L && GestureHint.DoubleTapSide in hints) {
                            latestOnDoubleTapFeedback.value(
                                doubleTap.side,
                                actualDelta,
                                GestureHint.DoubleTapRipple in hints,
                            )
                        }
                    }

                    DoubleTapAction.TogglePlayPause -> if (playPauseAllowed) {
                        latestOnTogglePlayPause.value()
                    }
                }
            }
        }
    }

    // 跳转拖动进行中：按住不动没有指针事件，靠心跳让控制层的兜底不收尾。
    var seekDragActive by remember { mutableStateOf(false) }
    PreviewHeartbeat(dragging = seekDragActive) { latestOnSeekHeartbeat.value() }

    Box(
        modifier = modifier
            .pointerInput(gesturesEnabled, playPauseAllowed, seekAllowed) {
                tapGesture.cancel()
                if (!gesturesEnabled) return@pointerInput
                detectPlayerTaps(tapGesture, dispatchTap)
            }
            // 按下即上报一次操作：按住不动同样不隐藏，双指按到最后一指抬起才结束。
            .pointerInput(gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                val tracker = PlayerGesturePressTracker()
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            for (change in event.changes) {
                                if (change.pressed) {
                                    if (tracker.onDown(change.id.value)) latestOnGesturePress.value(true)
                                } else if (tracker.onUp(change.id.value)) {
                                    latestOnGesturePress.value(false)
                                }
                            }
                        }
                    }
                } finally {
                    if (tracker.pressing) latestOnGesturePress.value(false)
                }
            }
            .pointerInput(gesturesEnabled, seekAllowed, volumeAllowed) {
                if (!gesturesEnabled) return@pointerInput
                val threshold = VOLUME_DRAG_THRESHOLD_DP.dp.toPx()
                val touchSlop = viewConfiguration.touchSlop
                val seekDeadZonePx = PlayerSeekDragDeadZone.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val drag = PlayerDragGesture(threshold, touchSlop)
                    drag.start(gestureZone(down.position.x, size.width))
                    var seekDrag: PlayerSeekDrag? = null
                    // 手指当前的累计位移：松手按它结算真实目标，死区只拦重复下发。
                    var lastSeekDragX = 0f
                    // 最后一次真正下发 seek 的位移与目标；只有越过死区才前进。
                    var lastSeekIssuedX: Float? = null
                    var lastSeekIssuedTarget: Long? = null
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
                                // 手势只重置计时，不唤出控制层。
                                latestOnGestureInteraction.value()
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
                                    ).also {
                                        seekDrag = it
                                        seekDragActive = true
                                    }
                                    lastSeekDragX = update.seekDragX
                                    // 目标恒为手指当前位置；死区只决定这一刻要不要重发 seek。
                                    seek.preview(update.seekDragX)?.let { target ->
                                        val requestSeek = seekBeyondDeadZone(
                                            previousPx = lastSeekIssuedX,
                                            currentPx = update.seekDragX,
                                            deadZonePx = seekDeadZonePx,
                                            lastIssuedTargetMillis = lastSeekIssuedTarget,
                                            targetMillis = target,
                                            targetDeadZoneMillis = PlaybackController.PREVIEW_SEEK_DEAD_ZONE_MILLIS,
                                        )
                                        if (requestSeek) {
                                            lastSeekIssuedX = update.seekDragX
                                            lastSeekIssuedTarget = target
                                        }
                                        latestOnSeekPreview.value(target, requestSeek)
                                        latestOnSeekHint.value(SeekHint(target, target - seek.baseMillis))
                                    }
                                }

                                null -> Unit
                            }
                        }
                    } finally {
                        seekDragActive = false
                        latestOnVolumeHint.value(null)
                        latestOnSeekHint.value(null)
                        // 松手、被转屏或输入中断都落定；落点就是手指当前位置，死区不改变落点。
                        seekDrag?.finish(lastSeekDragX)?.let { target ->
                            latestOnSeekPreview.value(target, false)
                            latestOnSeekCommit.value()
                        }
                    }
                }
            }
            // 滚轮调时长：中间区域上滚后退、下滚前进，连续滚动累积；停滚由控制层收尾。
            .pointerInput(gesturesEnabled, seekAllowed) {
                if (!gesturesEnabled || !seekAllowed) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (gestureZone(change.position.x, size.width) != GestureZone.Center) continue
                        val step = wheelSeekStep(change.scrollDelta.y) ?: continue
                        val target = latestOnSeekStep.value(step) ?: continue
                        change.consume()
                        latestOnGestureInteraction.value()
                        latestOnSeekHint.value(SeekHint(target, target - latestPosition.value))
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

/** 拖动进度时的目标时间与增减量提示：居中两行、白字、无底板；目标时间明显大于增减量。 */
@Composable
private fun SeekHintLabel(hint: SeekHint, modifier: Modifier = Modifier) {
    val shadow = Shadow(
        color = Color.Black.copy(alpha = 0.9f),
        offset = Offset(0f, 2f),
        blurRadius = 14f,
    )
    val targetStyle = MaterialTheme.typography.titleMedium.copy(
        color = Color.White,
        fontSize = PlayerSeekHintTargetFontSize,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
        shadow = shadow,
    )
    val deltaStyle = targetStyle.copy(fontSize = PlayerSeekHintDeltaFontSize)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = formatDuration(hint.targetMillis), style = targetStyle, maxLines = 1)
        Text(
            text = seekHintDeltaText(hint.deltaMillis),
            style = deltaStyle,
            maxLines = 1,
            modifier = Modifier.padding(top = PlayerSeekHintLineGap),
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
    onBlankTap: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(PlayerTopBarGradient)
            .then(
                if (onBlankTap == null) Modifier.blockPlayerGestures()
                else Modifier.tapToDismiss(onBlankTap),
            )
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlayerTopBarHeight)
                .padding(horizontal = AppSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButtonSurface(
                onClick = onBack,
                enabled = interactive,
                modifier = Modifier.size(PlayerSideTouchTarget),
            ) {
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
            PlayerIconButtonSurface(
                onClick = onRotate,
                enabled = interactive && rotateEnabled,
                modifier = Modifier.size(PlayerSideTouchTarget),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_screen_rotation),
                    contentDescription = stringResource(R.string.player_rotate),
                    tint = if (rotateAppearanceEnabled) AppColors.Foreground else DisabledToolColor,
                )
            }
            PlayerIconButtonSurface(
                onClick = onToggleMore,
                enabled = interactive && moreEnabled,
                modifier = Modifier.size(PlayerSideTouchTarget),
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
    PlayerIconButtonSurface(
        onClick = onTap,
        modifier = modifier.size(PlayerSideTouchTarget),
    ) {
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
    seekSettlement: SeekSettlement?,
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
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Long, Boolean) -> Unit,
    onSeekCommit: () -> Unit,
    onSeekHeartbeat: () -> Unit,
    onTogglePlaylist: () -> Unit,
    onToggleSubtitleMenu: () -> Unit,
    onLock: () -> Unit,
    onSelectSubtitle: (SubtitleMenuItem) -> Unit,
    modifier: Modifier = Modifier,
    onBlankTap: (() -> Unit)? = null,
) {
    // 图标条有意全宽贴边：三键居中与右侧按钮排布需要全部可用宽度；进度条与面板仍保留水平内边距。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PlayerBottomBarGradient)
            .then(
                if (onBlankTap == null) Modifier.blockPlayerGestures()
                else Modifier.tapToDismiss(onBlankTap),
            )
            .safeDrawingPadding()
            .padding(top = 24.dp, bottom = AppSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
    ) {
        if (subtitleMenuVisible) {
            SubtitlePanel(
                menu = subtitleMenuItems(subtitleTracks, externalSubtitles, subtitleSelection),
                onSelect = onSelectSubtitle,
                interactive = interactive &&
                    actionAllowed(PlayerAction.Subtitles, switching, initialLoading, restoring),
                modifier = Modifier.padding(horizontal = AppSpacing.Lg),
            )
        }
        SeekBar(
            mediaKey = playlist.getOrNull(currentIndex)?.location.orEmpty(),
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            previewMillis = seekPreviewMillis,
            seekSettlement = seekSettlement,
            // 面板打开时轨道让给关闭层：不拖动，也不改播放位置。
            enabled = onBlankTap == null &&
                interactive &&
                actionAllowed(PlayerAction.Seek, switching, initialLoading, restoring),
            appearanceEnabled = actionLooksEnabled(
                PlayerAction.Seek,
                canGoPrevious = currentIndex > 0,
                canGoNext = currentIndex in 0 until playlist.lastIndex,
                switching = switching,
                initialLoading = initialLoading,
                restoring = restoring,
            ),
            onPreview = onSeekPreview,
            onCommit = onSeekCommit,
            onHeartbeat = onSeekHeartbeat,
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
            onPlayPause = onPlayPause,
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
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleSubtitles: () -> Unit,
    onTogglePlaylist: () -> Unit,
    onLock: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val metrics = playerIconBarMetrics(maxWidth)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.transportButtonHeight),
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
                    .size(metrics.sideTouchTarget),
                iconSize = metrics.sideIconSize,
            )
            // 上一集在播放键左侧、下一集在右侧，播放键始终居中。
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
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
                    modifier = Modifier.size(
                        metrics.transportButtonWidth,
                        metrics.transportButtonHeight,
                    ),
                    iconSize = metrics.transportIconSize,
                )
                PlayerIconButton(
                    icon = if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                    contentDescription = stringResource(
                        if (playing) R.string.player_pause else R.string.player_play,
                    ),
                    onClick = onPlayPause,
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
                    modifier = Modifier.size(
                        metrics.transportButtonWidth,
                        metrics.transportButtonHeight,
                    ),
                    iconSize = metrics.playPauseIconSize,
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
                    modifier = Modifier.size(
                        metrics.transportButtonWidth,
                        metrics.transportButtonHeight,
                    ),
                    iconSize = metrics.transportIconSize,
                )
            }
            // 字幕紧挨播放列表放在右下区域：通道不够宽时随整条按钮等比缩小，不改成上下排列。
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
                    modifier = Modifier.size(metrics.sideTouchTarget),
                    iconSize = metrics.sideIconSize,
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
                    modifier = Modifier.size(metrics.sideTouchTarget),
                    iconSize = metrics.sideIconSize,
                )
            }
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

/** 禁用控件的画布色。 */
private val DisabledToolColor = AppColors.Faint.copy(alpha = 0.45f)

/** 播放页统一按压反馈的白色涟漪：按下约 20% 白，键盘焦点约 12% 白，悬停与拖动不显示背景。 */
@OptIn(ExperimentalMaterial3Api::class)
private val PlayerPressRippleConfiguration = RippleConfiguration(
    color = PlayerPressRippleColor,
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0f,
        focusedAlpha = 0.12f,
        hoveredAlpha = 0f,
        pressedAlpha = PLAYER_PRESS_RIPPLE_ALPHA,
    ),
)

/** 按目标区域选择点击指示；覆盖范围外不显示涟漪。 */
@Composable
private fun playerPressIndication(target: PlayerPressTarget): Indication? =
    if (playerPressFeedbackEnabled(target)) ripple() else null

/** 播放页图标按钮的热区：点击范围与涟漪范围都取 [modifier] 的尺寸。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerIconButtonSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRippleConfiguration provides PlayerPressRippleConfiguration,
    ) {
        Box(
            modifier = modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = playerPressIndication(PlayerPressTarget.IconButton),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

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
    PlayerIconButtonSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
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
    seekSettlement: SeekSettlement?,
    onPreview: (Long, Boolean) -> Unit,
    onCommit: () -> Unit,
    onHeartbeat: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    appearanceEnabled: Boolean = true,
) {
    val maximum = durationMillis.coerceAtLeast(1L)
    // 预览状态绑定到当前媒体与时长：切集后不沿用上一集的 seek 目标。
    var dragMillis by remember(mediaKey, durationMillis) { mutableStateOf<Long?>(null) }
    var heldMillis by remember(mediaKey, durationMillis) { mutableStateOf<Long?>(null) }
    // 已撤到的落定通知序号：只有更大的 id 才能撤掉当前保持值。
    var heldSettlementId by remember(mediaKey) { mutableLongStateOf(0L) }
    var sliderDragging by remember(mediaKey) { mutableStateOf(false) }
    // 滑杆落点先取当前播放位置：没有值变化就收尾时不能提交 0。
    var sliderMillis by remember(mediaKey) { mutableLongStateOf(positionMillis) }
    var leftTimeWidthPx by remember { mutableFloatStateOf(0f) }
    var rightTimeWidthPx by remember { mutableFloatStateOf(0f) }
    var dragTimeWidthPx by remember(mediaKey, durationMillis) { mutableStateOf<Float?>(null) }
    val previewForDisplay = dragMillis ?: previewMillis ?: heldMillis
    val progress = seekBarProgressMillis(positionMillis, durationMillis, previewForDisplay)
    val unknownText = stringResource(R.string.player_time_unknown)
    val labels = seekBarLabels(positionMillis, durationMillis, previewForDisplay, unknownText)
    val sliderEnabled = enabled && labels.seekEnabled
    // 按住进度条不动时没有指针事件；用心跳告诉控制层预览还活着。
    PreviewHeartbeat(dragging = dragMillis != null || sliderDragging, onHeartbeat = onHeartbeat)
    // 松手后保留末次预览，收到控制层的落定通知再撤掉，避免左侧时间与圆球回跳。
    // 同一目标连续落定时 id 递增，旧通知不会误清新保持值。
    LaunchedEffect(seekSettlement?.id) {
        val settlement = seekSettlement ?: return@LaunchedEffect
        if (!seekSettlementReleasesHold(heldSettlementId, settlement.id)) return@LaunchedEffect
        heldMillis = null
        heldSettlementId = settlement.id
    }
    LaunchedEffect(heldMillis) {
        if (heldMillis == null) return@LaunchedEffect
        delay(SEEK_PREVIEW_HOLD_MILLIS)
        // 落定通知由播放核心执行回报驱动；等不到通知时界面到点自行放掉保持值。
        heldMillis = null
    }
    val previewAt: (Long, Boolean) -> Unit = { target, requestSeek ->
        heldMillis = null
        onPreview(target, requestSeek)
    }
    val commitAt: (Long) -> Unit = { target ->
        heldMillis = target
        onCommit()
    }
    val latestPreviewAt = rememberUpdatedState(previewAt)
    val latestCommitAt = rememberUpdatedState(commitAt)
    // 未拖动时落点跟随播放位置，避免收尾提交旧值。
    LaunchedEffect(positionMillis, sliderDragging) {
        if (!sliderDragging) sliderMillis = positionMillis
    }
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
    // 切集与恢复原片期间拖动被拦截，外观同点击判据一起置灰。
    val timeColor = if (appearanceEnabled) AppColors.Foreground else DisabledToolColor
    val latestLeftTimeWidthPx = rememberUpdatedState(leftTimeWidthPx)
    val latestRightTimeWidthPx = rememberUpdatedState(rightTimeWidthPx)
    val density = LocalDensity.current
    val latestSpacingPx = rememberUpdatedState(with(density) { PlayerSeekTimeGap.toPx() })
    val latestThumbRadiusPx = rememberUpdatedState(with(density) { PlayerSeekThumbSize.toPx() / 2f })
    val frozenTimeWidth = dragTimeWidthPx?.let { with(density) { it.toDp() } }

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
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
                    // 滑杆入口（无障碍与外部驱动）每次都给真实目标：死区只用于指针拖动。
                    sliderDragging = true
                    sliderMillis = it.toLong()
                    previewAt(it.toLong(), true)
                },
                onValueChangeFinished = {
                    sliderDragging = false
                    commitAt(sliderMillis)
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
                            val deadZonePx = PlayerSeekDragDeadZone.toPx()
                            // 最后一次真正下发 seek 的手指位置与目标：死区只拦重复下发，不改变目标。
                            var lastSeekX = down.position.x
                            var lastSeekTarget = target
                            // 手指当前横坐标：松手按它结算落定目标。
                            var pointerX = down.position.x
                            down.consume()
                            dragMillis = target
                            latestPreviewAt.value(target, true)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                    val pressedIds = event.changes
                                        .filter { it.pressed }
                                        .map { it.id.value }
                                    if (isMultiPointerDrag(down.id.value, pressedIds)) break
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    pointerX = change.position.x
                                    // 每次移动都按手指真实位置更新目标与显示；只有越过死区才重发 seek。
                                    seekBarMillisAt(
                                        x = pointerX,
                                        trackStart = bounds.start,
                                        trackEnd = bounds.end,
                                        durationMillis = maximum,
                                    )?.let { updated ->
                                        target = updated
                                        dragMillis = target
                                        val requestSeek = seekBeyondDeadZone(
                                            previousPx = lastSeekX,
                                            currentPx = pointerX,
                                            deadZonePx = deadZonePx,
                                            lastIssuedTargetMillis = lastSeekTarget,
                                            targetMillis = target,
                                            targetDeadZoneMillis = PlaybackController.PREVIEW_SEEK_DEAD_ZONE_MILLIS,
                                        )
                                        if (requestSeek) {
                                            lastSeekX = pointerX
                                            lastSeekTarget = target
                                        }
                                        latestPreviewAt.value(target, requestSeek)
                                    }
                                }
                            } finally {
                                // 松手、多指取消或手势被系统收回都走这里：复位拖动状态并按最后目标落定，只提交一次。
                                dragMillis = null
                                dragTimeWidthPx = null
                                sliderDragging = false
                                latestCommitAt.value(target)
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
    enabled: Boolean,
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
            enabled = enabled,
            onClick = { toggle(MoreSection.Decode) },
        )
        if (expanded == MoreSection.Decode) {
            decodeModeOptions.forEach { option ->
                MoreOptionRow(
                    label = optionText(option.label),
                    checked = isOptionChecked(option, decodeMode),
                    enabled = enabled,
                    onClick = { onSelectDecodeMode(option.value) },
                )
            }
        }
        MoreChoiceRow(
            label = stringResource(R.string.player_loop),
            value = optionText(OptionLabel(loopModeLabel(loopMode))),
            expanded = expanded == MoreSection.Loop,
            enabled = enabled,
            onClick = { toggle(MoreSection.Loop) },
        )
        if (expanded == MoreSection.Loop) {
            loopModeOptions.forEach { option ->
                MoreOptionRow(
                    label = optionText(option.label),
                    checked = isOptionChecked(option, loopMode),
                    enabled = enabled,
                    onClick = { onSelectLoopMode(option.value) },
                )
            }
        }
        MoreChoiceRow(
            label = stringResource(R.string.player_orientation),
            value = optionText(OptionLabel(orientationModeLabel(orientationMode))),
            expanded = expanded == MoreSection.Orientation,
            enabled = enabled,
            onClick = { toggle(MoreSection.Orientation) },
        )
        if (expanded == MoreSection.Orientation) {
            orientationModeOptions.forEach { option ->
                MoreOptionRow(
                    label = optionText(option.label),
                    checked = isOptionChecked(option, orientationMode),
                    enabled = enabled,
                    onClick = { onSelectOrientationMode(option.value) },
                )
            }
        }
        MoreSwitchRow(
            label = stringResource(R.string.player_auto_play_next),
            checked = autoPlayNext,
            enabled = enabled,
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
            enabled = enabled,
            onClick = { toggle(MoreSection.SleepTimer) },
        )
        if (expanded == MoreSection.SleepTimer) {
            sleepTimerOptions.forEach { option ->
                MoreOptionRow(
                    label = optionText(option.label),
                    checked = isSleepTimerOptionChecked(option.value, selectedSleepTimerOption),
                    enabled = enabled,
                    onClick = { onSelectSleepTimer(option.value) },
                )
            }
            CustomSleepTimerRow(onSelect = onSelectSleepTimer, enabled = enabled)
            if (sleepTimerState != SleepTimerState.Off) {
                PlayerTextButton(
                    text = stringResource(R.string.player_sleep_timer_cancel),
                    onClick = onCancelSleepTimer,
                    enabled = enabled,
                    modifier = Modifier.padding(horizontal = AppSpacing.Md),
                )
            }
        }
    }
}

/** 面板根行：标题、当前取值与展开箭头。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreChoiceRow(
    label: String,
    value: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val expandedState = stringResource(
        if (expanded) R.string.player_more_expanded else R.string.player_more_collapsed,
    )
    CompositionLocalProvider(
        LocalRippleConfiguration provides PlayerPressRippleConfiguration,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (enabled) Modifier.playerPressClickable(PlayerPressTarget.MorePanelRow, onClick)
                    else Modifier,
                )
                .semantics { stateDescription = expandedState }
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) AppColors.Foreground else DisabledToolColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) AppColors.Muted else DisabledToolColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (enabled) AppColors.Faint else DisabledToolColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 展开后的勾选项。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreOptionRow(label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(
        LocalRippleConfiguration provides PlayerPressRippleConfiguration,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = checked,
                    enabled = enabled,
                    onClick = onClick,
                    role = Role.RadioButton,
                    interactionSource = interaction,
                    indication = playerPressIndication(PlayerPressTarget.MorePanelRow),
                )
                .padding(start = AppSpacing.Xxl, end = AppSpacing.Lg, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> DisabledToolColor
                    checked -> AppColors.Accent
                    else -> AppColors.Foreground
                },
                modifier = Modifier.weight(1f),
            )
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (enabled) AppColors.Accent else DisabledToolColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 自动连播开关行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSwitchRow(label: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(
        LocalRippleConfiguration provides PlayerPressRippleConfiguration,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    onValueChange = { onToggle() },
                    role = Role.Switch,
                    interactionSource = interaction,
                    indication = playerPressIndication(PlayerPressTarget.AutoPlayNextRow),
                )
                .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) AppColors.Foreground else DisabledToolColor,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                enabled = enabled,
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
}

/** 定时暂停的自定义分钟数输入。 */
@Composable
private fun CustomSleepTimerRow(onSelect: (SleepTimerOption) -> Unit, enabled: Boolean) {
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
            enabled = enabled,
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
            enabled = enabled,
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

/** 播放列表抽屉：横屏占右半屏、竖屏占下半屏，统一低透明度黑底，当前项居中。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDrawer(
    playlist: List<MediaFile>,
    currentIndex: Int,
    placement: PlaylistDrawerPlacement,
    onSelect: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerPlaylistBackgroundColor)
            // 抽屉只避让真正接触的屏边：横屏为上、下、末端，竖屏为左、右、底部；
            // 条目内容左右各留 16dp，当前项高亮左侧 0dp、右侧 4dp；不接触的一侧不叠加 inset。
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    when (placement) {
                        PlaylistDrawerPlacement.RightHalf ->
                            WindowInsetsSides.Vertical + WindowInsetsSides.End
                        PlaylistDrawerPlacement.BottomHalf ->
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    },
                ),
            ),
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
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PlayerPlaylistItemHeight)
                            .drawBehind {
                                if (active) {
                                    val bounds = playlistHighlightBounds(
                                        rowWidthPx = size.width,
                                        startInsetPx = PlayerPlaylistHighlightStartInset.toPx(),
                                        endInsetPx = PlayerPlaylistHighlightEndInset.toPx(),
                                    )
                                    drawRect(
                                        color = PlayerPlaylistCurrentItemColor,
                                        topLeft = Offset(bounds.start, 0f),
                                        size = Size(bounds.end - bounds.start, size.height),
                                    )
                                }
                            }
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onSelect(media) },
                            ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = PlayerPlaylistHorizontalInset),
                        ) {
                            Text(
                                text = playlistItemIndexLabel(index),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                                color = PlayerPlaylistTextColor,
                                maxLines = 1,
                            )
                            Text(
                                text = media.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = PlayerPlaylistTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = PlayerPlaylistIndexGap),
                            )
                        }
                        // 整行只有一个点击节点；涟漪由同一交互源驱动，覆盖到高亮条范围。
                        CompositionLocalProvider(
                            LocalRippleConfiguration provides PlayerPressRippleConfiguration,
                        ) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .padding(end = PlayerPlaylistHighlightEndInset)
                                    .indication(
                                        interactionSource = interaction,
                                        indication = playerPressIndication(
                                            PlayerPressTarget.PlaylistRow,
                                        ),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 字幕菜单：内嵌轨道、可匹配外挂、关闭，当前生效项用强调色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitlePanel(
    menu: List<SubtitleMenuItem>,
    onSelect: (SubtitleMenuItem) -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalRippleConfiguration provides PlayerPressRippleConfiguration,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .playerPanel()
                // 面板自己的区域拦下点击，空白处不落到栏的关闭层。
                .blockPlayerGestures(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = menu) { item ->
                    Text(
                        text = subtitleItemLabel(item),
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            !interactive -> DisabledToolColor
                            item.active -> AppColors.Accent
                            else -> AppColors.Foreground
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = playerPressIndication(PlayerPressTarget.SubtitleRow),
                                enabled = interactive,
                                onClick = { onSelect(item) },
                            )
                            .padding(horizontal = AppSpacing.Md, vertical = 10.dp),
                    )
                }
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
