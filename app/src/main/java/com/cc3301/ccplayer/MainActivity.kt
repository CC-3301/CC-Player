package com.cc3301.ccplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.cc3301.ccplayer.diagnostics.Diagnostics
import com.cc3301.ccplayer.diagnostics.DiagnosticsExporter
import com.cc3301.ccplayer.diagnostics.connectionLogLine
import com.cc3301.ccplayer.diagnostics.logLibraryNavigation
import com.cc3301.ccplayer.diagnostics.screenOrientationChangeLine
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaKind
import com.cc3301.ccplayer.library.MediaPreviewSource
import com.cc3301.ccplayer.library.mediaKindOf
import com.cc3301.ccplayer.network.DataStoreBookmarkStore
import com.cc3301.ccplayer.network.EncryptedCredentialStore
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.network.RoutingNetworkStreams
import com.cc3301.ccplayer.network.SourceProtocol
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.OPEN_PLAYER_ACTION
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackService
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.SeekSettlement
import com.cc3301.ccplayer.playback.SleepTimerOption
import com.cc3301.ccplayer.playback.SleepTimerState
import com.cc3301.ccplayer.playback.SubtitleSelection
import com.cc3301.ccplayer.playback.SubtitleTrack
import com.cc3301.ccplayer.playback.SurfacePresentation
import com.cc3301.ccplayer.playback.SurfaceSession
import com.cc3301.ccplayer.playback.isAtEpisodeEnd
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.smb.SmbJConnector
import com.cc3301.ccplayer.smb.SmbLocation
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbStreams
import com.cc3301.ccplayer.streaming.StaleSourceDiscardPolicy
import com.cc3301.ccplayer.ui.AppScreen
import com.cc3301.ccplayer.ui.EntryPhase
import com.cc3301.ccplayer.ui.FailedEntryExit
import com.cc3301.ccplayer.ui.HomeBackResult
import com.cc3301.ccplayer.ui.HomeScreen
import com.cc3301.ccplayer.ui.LibraryBackAction
import com.cc3301.ccplayer.ui.LibraryEntryAction
import com.cc3301.ccplayer.ui.LibraryScreen
import com.cc3301.ccplayer.ui.LibraryViewModel
import com.cc3301.ccplayer.ui.MediaVolumeControl
import com.cc3301.ccplayer.ui.MpvTextureView
import com.cc3301.ccplayer.ui.NavigationViewModel
import com.cc3301.ccplayer.ui.NotificationOpenAction
import com.cc3301.ccplayer.ui.PendingPlayback
import com.cc3301.ccplayer.ui.PlaybackEntryOpener
import com.cc3301.ccplayer.ui.PlaybackEntryState
import com.cc3301.ccplayer.ui.PlayerBackAction
import com.cc3301.ccplayer.ui.PlayerLevelControls
import com.cc3301.ccplayer.ui.PlayerScreen
import com.cc3301.ccplayer.ui.PlayerSessionOrientation
import com.cc3301.ccplayer.ui.ScreenOrientation
import com.cc3301.ccplayer.ui.SettingsScreen
import com.cc3301.ccplayer.ui.SettingsViewModel
import com.cc3301.ccplayer.ui.SourceListScreen
import com.cc3301.ccplayer.ui.SubtitleMenuItem
import com.cc3301.ccplayer.ui.failedEntryExit
import com.cc3301.ccplayer.ui.libraryEntryAction
import com.cc3301.ccplayer.ui.isPreparingPhase
import com.cc3301.ccplayer.ui.mediaFailureText
import com.cc3301.ccplayer.ui.notificationOpenAction
import com.cc3301.ccplayer.ui.playerBackAction
import com.cc3301.ccplayer.ui.playerTopBarBackEndsPlayback
import com.cc3301.ccplayer.ui.onPlayPauseClick
import com.cc3301.ccplayer.ui.relativeSeekTarget
import com.cc3301.ccplayer.ui.screenOrientationMode
import com.cc3301.ccplayer.ui.theme.CcPlayerTheme
import com.cc3301.ccplayer.webdav.OkHttpWebDavTransport
import com.cc3301.ccplayer.webdav.WebDavAddress
import com.cc3301.ccplayer.webdav.WebDavLocator
import com.cc3301.ccplayer.webdav.WebDavStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity(), MpvTextureView.Callback {

    private val navigation: NavigationViewModel by viewModels {
        NavigationViewModel.factory(applicationContext, diagnostics)
    }

    private val library: LibraryViewModel by viewModels {
        LibraryViewModel.factory(applicationContext, navigation, diagnostics)
    }

    private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModel.factory(applicationContext) }

    private val mediaPreview: MediaPreviewSource get() = (application as CcPlayerApplication).mediaPreview

    private val settings: SettingsRepository by lazy { SettingsRepository.create(applicationContext) }

    private val diagnostics get() = (application as CcPlayerApplication).diagnostics

    private val diagnosticsExporter by lazy {
        DiagnosticsExporter(
            logDirectory = File(filesDir, Diagnostics.DIRECTORY_NAME),
            outputDirectory = File(cacheDir, Diagnostics.DIRECTORY_NAME),
            systemInfo = diagnosticsSystemInfo(),
        )
    }

    /** 导出文件开头写入的版本与设备信息；取不到版本时标为未知。 */
    @Suppress("DEPRECATION")
    private fun diagnosticsSystemInfo(): List<String> {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "未知"
        return listOf(
            "应用版本: $version",
            "设备型号: ${Build.MODEL}",
            "系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var playback: PlaybackService.LocalBinder? = null
    private var bound = false

    /** Surface 的挂载与分离由会话持有；拆挂只由 Surface 生命周期回调触发。 */
    private val surfaceSession by lazy {
        SurfaceSession(
            attach = { surface ->
                playback?.engine?.let {
                    it.attachSurface(surface)
                    true
                } ?: false
            },
            startOutput = { playback?.engine?.startVideoOutput() },
            detachEngine = { playback?.engine?.detachSurface() },
            resize = { width, height -> playback?.engine?.setSurfaceSize(width, height) },
            onAvailabilityChanged = { available ->
                playback?.controller?.setRenderSurfaceAvailable(available)
            },
        )
    }

    /** 后台准备完成的播放请求，等播放服务与 Surface 就绪后起播。 */
    private val pendingPlayback = PendingPlayback()

    private var stateCollection: Job? = null
    private var streams: NetworkStreams? = null

    /** 退到后台丢弃字节源的判定：加载期间记待丢弃，加载结束或回前台前补做。 */
    private val staleSourceDiscardPolicy = StaleSourceDiscardPolicy()

    /** 屏幕常亮标志：仅前台且播放或加载时打开，其余时候释放。 */
    private val screenOn = ScreenOnFlag { keepOn ->
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private var playerVisible by mutableStateOf(false)

    /** 最近一次播放是否以失败收尾；返回时长按错误态处理。 */
    private var playbackFailed = false

    /** 「APP 内后台播放」当前取值：离开播放页时决定是否停止播放。 */
    private var inAppBackgroundPlayback = SettingsRepository.DEFAULT_IN_APP_BACKGROUND_PLAYBACK

    /** 本次播放的转屏结果与会话比例；为空时按设置判定，退出播放页即清除转屏结果。 */
    private val sessionOrientation = PlayerSessionOrientation()

    /** 点开的条目是否没有视频方向；加载开始前方向判定用它，加载开始后以视频轨结果为准。 */
    private var entryHasNoVideo = false

    /** 上一次记录过的屏幕方向；方向真正变化时据此写一条诊断日志。 */
    private var lastScreenOrientation = ScreenOrientation.Portrait

    /** 屏幕当前的四向方向；只在系统配置真正变化时更新，请求的方向本身不算到位。 */
    private var screenOrientation by mutableStateOf(ScreenOrientation.Portrait)
    private var playbackState by mutableStateOf<PlaybackState>(PlaybackState.Idle)
    private var progress by mutableStateOf(PlaybackProgress())
    private var seekPreviewMillis by mutableStateOf<Long?>(null)
    private var seekSettlement by mutableStateOf<SeekSettlement?>(null)
    private var playlist by mutableStateOf<List<MediaFile>>(emptyList())
    private var currentIndex by mutableIntStateOf(NO_MEDIA)
    private var decodeMode by mutableStateOf(DecodeMode.Hardware)
    private var loopMode by mutableStateOf(LoopMode.Sequential)
    private var sleepTimerState by mutableStateOf(SleepTimerState.Off)
    private var sleepTimerOption by mutableStateOf<SleepTimerOption?>(null)
    private var switching by mutableStateOf(false)
    private var restoring by mutableStateOf(false)
    private var buffering by mutableStateOf(false)

    /** 浏览页进入播放页的准备态与首次加载门禁。 */
    private val playbackEntry = PlaybackEntryState()

    /** 点开媒体：视频先进入播放页，音频等媒体准备完成后进入。 */
    private val playbackEntryOpener by lazy {
        PlaybackEntryOpener(
            scope = lifecycleScope,
            entryState = playbackEntry,
            directoryEntries = { library.directoryEntries() },
            networkStreams = { networkStreams() },
            onEnterPlayer = { enterPlayer(newSession = true) },
            onStartPlayback = ::openMedia,
            onShowFailure = ::showFailure,
            onLeavePlayer = ::leaveAfterFailedEntry,
            diagnostics = diagnostics,
        )
    }

    /** 屏幕上现在该显示什么：由控制层派生，界面只读。 */
    private var surfacePresentation by mutableStateOf(SurfacePresentation())

    /** 浏览页重新订阅可见项的代号：回前台时递增，让封面重试用尽的条目重新给一轮。 */
    private var libraryPreviewTick by mutableIntStateOf(0)

    /** 是否已经进过前台：第一次进入不改代号，避免刚建界面就多抽一轮。 */
    private var foregroundSeen = false

    private var subtitleTracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
    private var externalSubtitles by mutableStateOf<List<MediaFile>>(emptyList())
    private var subtitleSelection by mutableStateOf<SubtitleSelection>(SubtitleSelection.Off)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playback = binder
            // 字节源由界面侧持有：切集重试前的丢弃由这里注入。
            binder.controller.attachLoadSources(discardByteSources = ::discardByteSourcesForSwitch)
            stateCollection = lifecycleScope.launch {
                launch {
                    binder.controller.state.collect {
                        playbackState = it
                        updateKeepScreenOn()
                        // 退后台时加载中没丢成的字节源，加载一结束就补做；已回到前台则留给回前台补做。
                        if (it != PlaybackState.Loading) {
                            staleSourceDiscardPolicy.onLoadingFinished(
                                inBackground = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                                discard = ::discardByteSources,
                            )
                        }
                    }
                }
                launch { binder.controller.progress.collect { progress = it } }
                launch { binder.controller.seekPreviewMillis.collect { seekPreviewMillis = it } }
                launch { binder.controller.seekSettlement.collect { seekSettlement = it } }
                launch { binder.controller.playlist.collect { playlist = it } }
                launch { binder.controller.currentIndex.collect { currentIndex = it } }
                launch { binder.controller.decodeMode.collect { decodeMode = it } }
                launch { binder.controller.loopMode.collect { loopMode = it } }
                launch { binder.controller.sleepTimerState.collect { sleepTimerState = it } }
                launch { binder.controller.sleepTimerOption.collect { sleepTimerOption = it } }
                launch { binder.controller.switching.collect { switching = it } }
                launch { binder.controller.restoring.collect { restoring = it } }
                launch { binder.controller.playbackFailed.collect { playbackFailed = it } }
                launch { binder.controller.buffering.collect { buffering = it } }
                launch {
                    binder.controller.surfacePresentation.collect {
                        surfacePresentation = it
                        // 屏上那一帧的比例；有效就更新，离页或新会话时由会话对象作废。
                        sessionOrientation.onVideoAspectRatio(it.aspectRatio)
                    }
                }
                launch {
                    binder.controller.playbackFailures.collect {
                        // 引擎加载失败不提供具体原因，只能提示“播放失败”；来源探测失败另走具体原因。
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.player_playback_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        // 首次起播失败时没有可停留的画面，退回浏览页。
                        if (playbackEntry.consumeInitialFailure()) closePlayer()
                    }
                }
                launch { binder.controller.subtitleTracks.collect { subtitleTracks = it } }
                launch { binder.controller.externalSubtitles.collect { externalSubtitles = it } }
                launch { binder.controller.subtitleSelection.collect { subtitleSelection = it } }
                launch { settings.autoPlayNext.collect { binder.controller.setAutoPlayNext(it) } }
                launch { settings.resumeMode.collect { binder.controller.setResumeMode(it) } }
                launch { settings.decodeMode.collect { binder.controller.setDecodeMode(it) } }
            }
            surfaceSession.onEngineAvailable()
            // 新控制器默认认为 Surface 可用；连上后同步一次会话的真实状态。
            binder.controller.setRenderSurfaceAvailable(surfaceSession.available)
            binder.setAppInForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            startPendingPlayback()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playback = null
            surfaceSession.onEngineLost()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenOrientation = currentScreenOrientation()
        lastScreenOrientation = screenOrientation
        lifecycleScope.launch {
            settings.inAppBackgroundPlayback.collect { inAppBackgroundPlayback = it }
        }
        // 深色界面统一用浅色系统栏图标。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            CcPlayerTheme {
                val libraryState by library.state.collectAsState()
                val sourcesState by library.sourcesState.collectAsState()
                val settingsState by settingsViewModel.state.collectAsState()
                val navState by navigation.state.collectAsState()
                val coldStartPending by navigation.coldStartPending.collectAsState()
                val autoPlayNext by settings.autoPlayNext.collectAsState(
                    initial = SettingsRepository.DEFAULT_AUTO_PLAY_NEXT,
                )
                val orientationMode by settings.orientationMode.collectAsState(
                    initial = SettingsRepository.DEFAULT_ORIENTATION_MODE,
                )
                LaunchedEffect(playerVisible) { applyImmersive(playerVisible) }
                LaunchedEffect(navState.screen, navState.sourcesProtocol, playerVisible, coldStartPending) {
                    // 恢复判定完成前界面还没渲染，不产生导航日志。
                    if (coldStartPending) return@LaunchedEffect
                    diagnostics.log {
                        if (playerVisible) {
                            "导航: 播放页"
                        } else if (navState.screen == AppScreen.Sources) {
                            "导航: ${navState.screen.name} ${navState.sourcesProtocol.name}"
                        } else {
                            "导航: ${navState.screen.name}"
                        }
                    }
                }
                // 已接手后首次就绪或转入切集，入口阶段到此结束。
                LaunchedEffect(playbackState, switching) {
                    playbackEntry.onPlaybackUpdate(playbackState, switching)
                }
                // 等待接手兜底：只在该阶段的前台时间里累计；到点中止本次打开并回滚。
                LaunchedEffect(playbackEntry.phase, playerVisible) {
                    if (!playerVisible) return@LaunchedEffect
                    while (playbackEntry.phase == EntryPhase.AwaitingHandover) {
                        delay(ENTRY_HANDOVER_TICK_MILLIS)
                        val timedOut = playbackEntry.onHandoverTick(
                            deltaMillis = ENTRY_HANDOVER_TICK_MILLIS,
                            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                        )
                        if (timedOut && playbackEntry.abortAwaitingHandover()) {
                            diagnostics.log { "打开交接超时: 播放控制层未接手" }
                            leaveAfterFailedEntry()
                            break
                        }
                    }
                }
                // 播放页方向交给系统的传感器旋转模式：进页、改设置、点转屏、画面比例变化与离页时下发。
                // 比例跟着会话走：新会话作废，回到同一会话沿用，切集期间用屏上旧画面的比例。
                LaunchedEffect(
                    playerVisible,
                    orientationMode,
                    sessionOrientation.videoAspectRatio,
                    sessionOrientation.override,
                    // 进页时播放列表还没落地；媒介身份与视频轨变化后要重算一次「有无视频方向」。
                    playlist,
                    currentIndex,
                    surfacePresentation.targetHasVideo,
                ) {
                    // 不在播放页时传空：方向交回系统。
                    dispatchPlayerOrientation(if (playerVisible) orientationMode else null)
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) addRoot(uri)
                }

                val levelControls = remember {
                    PlayerLevelControls(volume = MediaVolumeControl(this@MainActivity))
                }

                // 播放页封面只给纯音频目标：直接读列表共用的封面缓存，不在这里另做解码。
                val currentMedia = playlist.getOrNull(currentIndex)
                val audioTarget = currentMedia?.let { mediaKindOf(it.name) == MediaKind.Audio } == true
                // 同一个目标进出播放页时不清空，避免封面闪一帧占位；只有换目标才清。
                var producedCoverLocation by remember { mutableStateOf<String?>(null) }
                val playerCover by produceState<File?>(
                    null,
                    currentMedia?.location,
                    currentMedia?.size,
                    currentMedia?.lastModified,
                    playerVisible,
                ) {
                    val target = currentMedia?.takeIf { playerVisible && audioTarget }
                    if (target != null && target.location != producedCoverLocation) {
                        // 目标变了才清空，避免音频切音频短暂留着上一张封面。
                        value = null
                        producedCoverLocation = target.location
                    }
                    if (target != null) value = mediaPreview.cachedImage(target)
                }

                when {
                    // 冷启动恢复判定完成前只显示纯色底：不画首页，也不逐级播放目录栈。
                    coldStartPending -> {
                        // 判定期间返回键忽略：不退出、不提示，也不取消本次恢复。
                        BackHandler {}
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                        )
                    }

                    playerVisible -> {
                        BackHandler { handlePlayerBack() }
                        PlayerScreen(
                            state = playbackState,
                            progress = progress,
                            seekPreviewMillis = seekPreviewMillis,
                            seekSettlement = seekSettlement,
                            playlist = playlist,
                            currentIndex = currentIndex,
                            decodeMode = decodeMode,
                            loopMode = loopMode,
                            orientationMode = orientationMode,
                            orientationOverride = sessionOrientation.override,
                            screenOrientation = screenOrientation,
                            autoPlayNext = autoPlayNext,
                            levelControls = levelControls,
                            surfaceCallback = this@MainActivity,
                            surfacePresentation = surfacePresentation,
                            coverFile = playerCover,
                            audioTarget = audioTarget,
                            switching = switching,
                            restoring = restoring,
                            buffering = playbackEntry.showsBuffering(buffering),
                            initialLoading = playbackEntry.initialLoading(playbackState, switching),
                            preparing = playbackEntry.phase.isPreparingPhase,
                            subtitleTracks = subtitleTracks,
                            externalSubtitles = externalSubtitles,
                            subtitleSelection = subtitleSelection,
                            onPlayPause = {
                                playback?.controller?.onPlayPauseClick()
                                    ?: diagnostics.log { "播放键未执行: 播放会话已断开" }
                            },
                            onPrevious = {
                                playback?.controller?.previous()
                                    ?: diagnostics.log { "上一集未执行: 播放会话已断开" }
                            },
                            onNext = {
                                playback?.controller?.next()
                                    ?: diagnostics.log { "下一集未执行: 播放会话已断开" }
                            },
                            onSeekPreview = { position, requestSeek ->
                                playback?.controller?.updateSeekPreview(position, requestSeek)
                            },
                            onSeekCommit = { playback?.controller?.commitSeekPreview() },
                            onSeekBy = { seekBy(it) },
                            onSeekStep = { playback?.controller?.updateSeekPreviewBy(it) },
                            onSeekHeartbeat = { playback?.controller?.seekPreviewHeartbeat() },
                            onPlaylistSelect = { media -> playback?.controller?.play(media) },
                            onSelectDecodeMode = { playback?.controller?.setDecodeMode(it) },
                            onSelectLoopMode = { playback?.controller?.setLoopMode(it) },
                            onSelectOrientationMode = { mode ->
                                sessionOrientation.clearOverride()
                                lifecycleScope.launch { settings.setOrientationMode(mode) }
                            },
                            onToggleAutoPlayNext = {
                                lifecycleScope.launch { settings.setAutoPlayNext(!autoPlayNext) }
                            },
                            onSelectSleepTimer = { playback?.controller?.startSleepTimer(it) },
                            onCancelSleepTimer = { playback?.controller?.cancelSleepTimer() },
                            sleepTimerState = sleepTimerState,
                            sleepTimerOption = sleepTimerOption,
                            onSelectSubtitle = { item ->
                                val controller = playback?.controller
                                when (item) {
                                    is SubtitleMenuItem.Embedded -> controller?.selectEmbeddedSubtitle(item.trackId)
                                    is SubtitleMenuItem.External -> controller?.selectExternalSubtitle(item.location)
                                    is SubtitleMenuItem.Off -> controller?.disableSubtitles()
                                }
                            },
                            onToggleRotation = {
                                sessionOrientation.toggle(currentScreenOrientation())
                            },
                            onClose = ::handlePlayerTopBarBack,
                        )
                    }

                    navState.screen == AppScreen.Settings -> {
                        BackHandler { navigation.closeSettings() }
                        SettingsScreen(
                            state = settingsState,
                            onSetAutoPlayNext = settingsViewModel::setAutoPlayNext,
                            onSetBackgroundPlayback = settingsViewModel::setBackgroundPlayback,
                            onSetInAppBackgroundPlayback = settingsViewModel::setInAppBackgroundPlayback,
                            onSetResumeMode = settingsViewModel::setResumeMode,
                            onSetAutoRestorePosition = settingsViewModel::setAutoRestorePosition,
                            onSetRestoreLastDirectory = settingsViewModel::setRestoreLastDirectory,
                            onSetDecodeMode = settingsViewModel::setDecodeMode,
                            onSetOrientationMode = settingsViewModel::setOrientationMode,
                            onSetDiagnosticsEnabled = settingsViewModel::setDiagnosticsEnabled,
                            onExportDiagnostics = ::exportDiagnostics,
                            onClose = navigation::closeSettings,
                        )
                    }

                    navState.screen == AppScreen.Home -> {
                        BackHandler { handleHomeBack() }
                        HomeScreen(
                            onOpenProtocol = { protocol ->
                                library.clearNetworkSourceForm()
                                navigation.openProtocol(protocol)
                            },
                            onOpenSettings = navigation::openSettings,
                        )
                    }

                    navState.screen == AppScreen.Sources -> {
                        BackHandler {
                            library.clearNetworkSourceForm()
                            navigation.backToHome()
                        }
                        SourceListScreen(
                            protocol = navState.sourcesProtocol,
                            state = sourcesState,
                            onOpenSettings = navigation::openSettings,
                            onAdd = {
                                if (navState.sourcesProtocol == SourceProtocol.Local) {
                                    picker.launch(null)
                                } else {
                                    library.openNewSourceForm(navState.sourcesProtocol)
                                }
                            },
                            onOpenSource = { source ->
                                diagnostics.log {
                                    connectionLogLine(source.protocol, source.name, source.location)
                                }
                                library.openSource(source)
                            },
                            onEditSource = library::openEditSourceForm,
                            onDeleteSource = library::removeSource,
                            onFormFieldChange = library::updateFormField,
                            onDismissForm = library::dismissNetworkSourceForm,
                            onSaveForm = library::saveNetworkSourceForm,
                        )
                    }

                    else -> {
                        BackHandler {
                            when (navigation.backFromLibrary()) {
                                LibraryBackAction.NavigateUp -> library.navigateUp()
                                LibraryBackAction.LeaveLibrary -> Unit
                            }
                        }
                        LibraryScreen(
                            state = libraryState,
                            mediaPreview = mediaPreview,
                            previewReloadTick = libraryPreviewTick,
                            onOpenEntry = { entry ->
                                logLibraryNavigation(
                                    diagnostics = diagnostics,
                                    rootName = libraryState.stack.firstOrNull()?.name ?: "",
                                    relativePath = libraryState.stack
                                        .drop(1)
                                        .joinToString("/") { it.name },
                                    entryName = entry.name,
                                    directory = entry.isDirectory,
                                )
                                if (entry.isDirectory) {
                                    library.enter(entry)
                                } else {
                                    val current = playlist.getOrNull(currentIndex)
                                    val reopen = libraryEntryAction(
                                        entryLocation = entry.location,
                                        currentLocation = current?.location,
                                        playbackActive = playbackState != PlaybackState.Idle,
                                    ) == LibraryEntryAction.ReopenPlayerPage
                                    if (reopen) {
                                        showPlayerPage()
                                    } else {
                                        val entryIsVideo = mediaKindOf(entry.name) == MediaKind.Video
                                        // 加载开始前列表与视频轨还是上一段会话的结论：首帧方向按点开的条目算。
                                        entryHasNoVideo = !entryIsVideo
                                        playbackEntryOpener.open(
                                            entry = entry,
                                            entries = libraryState.entries,
                                            enterBeforePreparing = entryIsVideo,
                                        )
                                    }
                                }
                            },
                            onOpenSettings = navigation::openSettings,
                            onSelectView = { choice ->
                                lifecycleScope.launch { settings.setView(choice.viewMode, choice.gridColumns) }
                            },
                            onSelectSort = { order -> lifecycleScope.launch { settings.setSortOrder(order) } },
                            onScrollToTop = library::scrollToTop,
                            onRetry = library::retry,
                            onSavePosition = library::savePosition,
                        )
                    }
                }
            }
        }
        consumeOpenPlayerIntent(intent)
    }

    /** 通知栏媒体卡片点击：回到已有播放会话的播放页。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeOpenPlayerIntent(intent)
    }

    /** 消费通知入口：清掉动作并回写 Intent，避免界面重建时重放；只在会话非空闲时回到播放页。 */
    private fun consumeOpenPlayerIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != OPEN_PLAYER_ACTION) return
        intent.action = null
        setIntent(intent)
        val open = notificationOpenAction(
            action = action,
            playbackState = playback?.controller?.state?.value,
            playerVisible = playerVisible,
        ) == NotificationOpenAction.ShowPlayerPage
        if (open) showPlayerPage()
    }

    /** 首页返回：第一次提示，2 秒内再按才退出。 */
    private fun handleHomeBack() {
        when (navigation.onHomeBack()) {
            HomeBackResult.ShowExitHint ->
                Toast.makeText(this, getString(R.string.app_exit_hint), Toast.LENGTH_SHORT).show()

            HomeBackResult.Exit -> finish()
        }
    }

    /** 双击三分区的快退/快进：返回夹取后的实际位移，时长未知时不动作。 */
    private fun seekBy(deltaMillis: Long): Long {
        val controller = playback?.controller ?: return 0L
        val target = relativeSeekTarget(
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            deltaMillis = deltaMillis,
        ) ?: return 0L
        controller.seekTo(target.targetMillis)
        return target.deltaMillis
    }

    /** 汇总轮转日志为一个纯文本文件，交给系统分享面板。 */
    private fun exportDiagnostics() {
        lifecycleScope.launch {
            val exported = withContext(Dispatchers.IO) {
                try {
                    Result.success(diagnosticsExporter.export())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
            if (exported.isFailure) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.settings_export_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val file = exported.getOrNull()
            if (file == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.settings_export_empty),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val uri = runCatching {
                FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
            }.getOrNull()
            if (uri == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.settings_export_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.settings_export_diagnostics)))
        }
    }

    /** 取持久读授权：目录选定后重启仍可读，不需要任何存储权限。 */
    private fun addRoot(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        library.addLocalRoot(uri.toString())
    }

    /**
     * 进入播放页。入口阶段由 [PlaybackEntryState] 在打开时先行建立，这里只负责界面与会话。
     *
     * [newSession] 为真表示开始新的播放会话（从浏览页点进来），比例与转屏结果作废；
     * 为假表示回到同一会话（通知栏入口等），沿用已记录的比例。
     */
    private fun enterPlayer(newSession: Boolean) {
        if (newSession) {
            sessionOrientation.beginPlayback()
            // 打开另一个目标：旧片立即停止出声、屏上旧画面撤掉，方向与比例按新目标判定。
            playback?.controller?.beginOpenHandover()
        } else {
            sessionOrientation.clearOverride()
        }
        playerVisible = true
        updateKeepScreenOn()
        requestNotificationPermission()
        if (!bound) {
            val intent = Intent(this, PlaybackService::class.java)
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, connection, BIND_AUTO_CREATE)
            bound = true
        }
    }

    /** 起播前连不上时用提示条告知。 */
    private fun showFailure(failure: MediaFailure) {
        Toast.makeText(this, getString(mediaFailureText(failure)), Toast.LENGTH_LONG).show()
    }

    /** 回环代理与网络会话按需建立，关播放页时一并释放。 */
    private fun networkStreams(): NetworkStreams = streams ?: createNetworkStreams().also { streams = it }

    private fun createNetworkStreams(): NetworkStreams {
        val sources = NetworkSourceRepository(
            store = DataStoreBookmarkStore.create(applicationContext),
            credentials = EncryptedCredentialStore.create(applicationContext),
        )
        val webDavStreams = WebDavStreams(OkHttpWebDavTransport(), WebDavLocator(sources), diagnostics)
        return RoutingNetworkStreams(
            mapOf(
                SmbLocation.SCHEME to SmbStreams(SmbJConnector(), SmbLocator(sources), diagnostics),
                WebDavAddress.SCHEME_HTTP to webDavStreams,
                WebDavAddress.SCHEME_HTTPS to webDavStreams,
            ),
        )
    }

    /** 后台准备完成：把播放列表、字幕目录与入口代际交给播放控制层。 */
    private fun openMedia(
        current: MediaFile,
        files: List<MediaFile>,
        directoryEntries: List<MediaFile>,
        generation: Int,
    ) {
        // 加载开始：目标的视频轨以加载结果为准，不再用点开时的扩展名事实。
        entryHasNoVideo = false
        pendingPlayback.set(current, files, directoryEntries, generation)
        startPendingPlayback()
    }

    /** 引擎就绪且 Surface 已就位时才开始播放。 */
    private fun startPendingPlayback() {
        val binder = playback ?: return
        pendingPlayback.start(canStart = surfaceSession.hasSurface) { current, files, entries, generation ->
            // 请求等待期间已经开始另一次打开：这次请求作废，不能起播旧目标。
            if (!playbackEntry.isCurrent(generation)) return@start
            val accepted = binder.controller.openPlaylist(files, current, entries, generation)
            if (!accepted) {
                // 目标不在列表：按失败收尾并给出既有失败提示，不静默返回。
                playbackEntry.failed(generation)
                showFailure(MediaFailure.Unknown)
                leaveAfterFailedEntry()
                return@start
            }
            playbackEntry.accepted(generation)
            // 播放可能在退后台之后才真正开始，开播后重新上报一次当前前后台。
            binder.setAppInForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    }

    /** 通知栏要能显示就得有这个权限（Android 13 起）。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 退到后台：不拆挂画面，屏幕停住的最后一帧原样保留。是否暂停由播放服务按后台播放设置决定。 */
    override fun onStop() {
        super.onStop()
        diagnostics.log("应用退到后台")
        val binder = playback
        // 窗口不可见不拆挂：Surface 由应用进程持有，视频输出与窗口句柄都保持原样。
        if (binder != null && surfaceSession.hasSurface) diagnostics.log("窗口不可见但 Surface 存活，未重建视频输出")
        binder?.setAppInForeground(false)
        // 退后台立即释放常亮，不等播放状态回调。
        updateKeepScreenOn()
        // 退后台时手势不会再收尾：落定预览并恢复声音，后台播放开着也一样。
        binder?.controller?.commitSeekPreview()
        // 后台且（后台播放关闭或已暂停）时才丢，地址不变，只打陈旧标记；
        // 加载中不能丢：当前响应正踩在这些源上，先记待丢弃，加载结束或回前台前补做。
        if (binder != null) {
            val state = binder.controller.state.value
            if (staleSourceDiscardPolicy.onLeftForeground(
                    backgroundPlaybackOff = binder.blocksBackgroundPlayback,
                    paused = state == PlaybackState.Paused,
                    loading = state == PlaybackState.Loading,
                )
            ) {
                discardByteSources()
            }
        }
    }

    /**
     * 丢弃已建立的字节源：只打陈旧标记，不取锁也不碰 I/O，当场做完——
     * 回前台后的第一条请求一定读到标记，不会先用旧源读一段再重建。
     * 返回是否真的丢了；还没有流时返回 false，待丢弃标志留着等下一次补做。
     */
    private fun discardByteSources(): Boolean {
        val networkStreams = streams ?: return false
        networkStreams.discardByteSources()
        return true
    }

    /** 切集重试前丢弃已建立的字节源：下一次加载重新建源；返回是否真的丢弃了源。 */
    private suspend fun discardByteSourcesForSwitch(): Boolean = discardByteSources()

    override fun onStart() {
        super.onStart()
        diagnostics.log("应用进入前台")
        playback?.setAppInForeground(true)
        updateKeepScreenOn()
        // 退后台时因加载中没丢成的字节源，回前台先补做、再让播放核心发起请求。
        staleSourceDiscardPolicy.onReturnedToForeground(discard = ::discardByteSources)
        // 回前台让浏览页重新订阅可见项：封面重试用尽（含 SMB 会话作废）的条目重新给一轮。
        if (foregroundSeen) libraryPreviewTick++
        foregroundSeen = true
    }

    /** 仅前台且播放页在场、播放或加载时保持屏幕常亮；暂停、片尾、离开播放页与退后台立即释放。 */
    private fun updateKeepScreenOn() {
        screenOn.update(
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            playerVisible = playerVisible,
            state = playbackState,
        )
    }

    override fun onSurfaceReady(surface: Surface) {
        surfaceSession.onSurfaceCreated(surface)
        startPendingPlayback()
    }

    override fun onSurfaceResized(width: Int, height: Int) {
        surfaceSession.onSurfaceChanged(width, height)
    }

    /**
     * 播放页系统返回：切集只取消这一跳；首次加载、恢复原片与错误态退出播放页；
     * 其余播放状态按「APP 内后台播放」决定离开后是否继续播放。
     */
    private fun handlePlayerBack() {
        when (
            playerBackAction(
                state = playbackState,
                switching = switching,
                restoring = restoring,
                initialLoading = playbackEntry.initialLoading(playbackState, switching),
                failed = playbackFailed,
                inAppBackgroundPlayback = inAppBackgroundPlayback,
                entryPhase = playbackEntry.phase,
                atEpisodeEnd = isAtEpisodeEnd(
                    playbackState,
                    progress.positionMillis,
                    progress.durationMillis,
                ),
            )
        ) {
            PlayerBackAction.CancelOpening -> leavePlayerPage()
            PlayerBackAction.CancelSwitch -> cancelSwitchOrReevaluate()
            PlayerBackAction.StopAndLeave -> closePlayer()
            PlayerBackAction.LeaveAndContinue -> leavePlayerPage()
        }
    }

    /** 顶栏返回：准备期与系统返回走同一套判定；其余阶段左上角返回总是结束播放。 */
    private fun handlePlayerTopBarBack() {
        if (playerTopBarBackEndsPlayback(playbackEntry.phase)) {
            closePlayer()
        } else {
            handlePlayerBack()
        }
    }

    /**
     * 取消切集；返回前切集已经结束时按控制层最新状态重新判定一次。
     * 竞态下不因取消失败直接停播；仅当播放会话不存在或最新状态要求退出时才关闭播放页。
     */
    private fun cancelSwitchOrReevaluate() {
        val binder = playback ?: run {
            closePlayer()
            return
        }
        if (binder.controller.cancelSwitch()) return
        val state = binder.controller.state.value
        val switching = binder.controller.switching.value
        val progress = binder.controller.progress.value
        when (
            playerBackAction(
                state = state,
                switching = switching,
                restoring = binder.controller.restoring.value,
                initialLoading = playbackEntry.initialLoading(state, switching),
                failed = binder.controller.playbackFailed.value,
                inAppBackgroundPlayback = inAppBackgroundPlayback,
                entryPhase = playbackEntry.phase,
                atEpisodeEnd = isAtEpisodeEnd(state, progress.positionMillis, progress.durationMillis),
            )
        ) {
            PlayerBackAction.CancelOpening -> leavePlayerPage()
            PlayerBackAction.CancelSwitch -> Unit
            PlayerBackAction.StopAndLeave -> closePlayer()
            PlayerBackAction.LeaveAndContinue -> leavePlayerPage()
        }
    }

    /**
     * 离开播放页但保持播放：播放列表、当前项与进度留在播放服务里，
     * 返回播放页时直接沿用，不重新解析目录或加载文件。
     */
    private fun leavePlayerPage() {
        playbackEntryOpener.cancel()
        // 打开没有真正开始就离开：撤回交接，旧片恢复出声与画面。
        playback?.controller?.cancelOpenHandover()
        pendingPlayback.clear()
        playbackEntry.cancelEntry()
        onSurfaceReleased()
        playerVisible = false
        restoreBrowserSystemUi()
        updateKeepScreenOn()
    }

    /** 入口失败收尾：有旧会话且不在片尾时只离开播放页；没有会话或旧会话停在片尾则结束播放。 */
    private fun leaveAfterFailedEntry() {
        val controller = playback?.controller
        val state = controller?.state?.value
        val progress = controller?.progress?.value
        val atEpisodeEnd = state != null && progress != null &&
            isAtEpisodeEnd(state, progress.positionMillis, progress.durationMillis)
        when (failedEntryExit(state, atEpisodeEnd)) {
            FailedEntryExit.LeavePlayerPage -> leavePlayerPage()
            FailedEntryExit.StopPlayback -> closePlayer()
        }
    }

    /** 回到播放页：沿用播放服务里的会话，不重建播放，也不重新加载文件。 */
    private fun showPlayerPage() {
        if (playerVisible) return
        val binder = playback ?: return
        if (binder.controller.state.value == PlaybackState.Idle) return
        enterPlayer(newSession = false)
    }

    override fun onSurfaceReleased() {
        // 只有 Surface 真正销毁才分离：窗口不可见不触发这个回调。
        surfaceSession.onSurfaceDestroyed()
    }

    private fun closePlayer() {
        playbackEntryOpener.cancel()
        playback?.controller?.stop()
        onSurfaceReleased()
        pendingPlayback.clear()
        playbackEntry.cancelEntry()
        playerVisible = false
        // 切回浏览页前恢复系统栏与方向，避免 insets 变化让浏览页布局跳动。
        restoreBrowserSystemUi()
        playbackState = PlaybackState.Idle
        playbackFailed = false
        updateKeepScreenOn()
        progress = PlaybackProgress()
        seekPreviewMillis = null
        seekSettlement = null
        playlist = emptyList()
        currentIndex = NO_MEDIA
        decodeMode = DecodeMode.Hardware
        loopMode = LoopMode.Sequential
        sleepTimerState = SleepTimerState.Off
        sleepTimerOption = null
        switching = false
        restoring = false
        buffering = false
        surfacePresentation = SurfacePresentation()
        subtitleTracks = emptyList()
        externalSubtitles = emptyList()
        subtitleSelection = SubtitleSelection.Off
        releaseNetworkStreams()
        stopPlaybackService()
    }

    private fun releaseNetworkStreams() {
        streams?.close()
        streams = null
    }

    private fun stopPlaybackService() {
        // 控制层可能比界面活得久：先摘下字节源出口，别把已销毁的界面留在服务里。
        playback?.controller?.attachLoadSources(discardByteSources = { false })
        stateCollection?.cancel()
        stateCollection = null
        playback = null
        if (bound) {
            unbindService(connection)
            bound = false
            stopService(Intent(this, PlaybackService::class.java))
        }
    }

    /**
     * 退出播放页：恢复系统栏与方向，保证它们在页面切换前到位。
     *
     * 覆盖清零、比例保留到下一次回到同一会话；上一段会话的视频轨结论作废，避免新会话首帧被它带偏。
     * 方向由 [dispatchPlayerOrientation] 按非播放状态下发，重复调用无副作用。
     */
    private fun restoreBrowserSystemUi() {
        applyImmersive(false)
        sessionOrientation.clearOverride()
        surfacePresentation = surfacePresentation.copy(targetHasVideo = true)
        // 不在播放页：模式传空，方向交回系统；比例留到下一次回到同一会话。
        dispatchPlayerOrientation(null)
    }

    /** 当前屏幕的四向方向，供转屏按钮从现状切换与方向日志判定。 */
    private fun currentScreenOrientation(): ScreenOrientation =
        when (displayRotation()) {
            Surface.ROTATION_90 -> ScreenOrientation.Landscape
            Surface.ROTATION_180 -> ScreenOrientation.ReversePortrait
            Surface.ROTATION_270 -> ScreenOrientation.ReverseLandscape
            else -> ScreenOrientation.Portrait
        }

    /** 显示旋转值；180° 反向与正向同属一个族，只能由它区分。 */
    private fun displayRotation(): Int =
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0

    /**
     * 下发方向；这是 `requestedOrientation` 唯一的写入点。
     *
     * [mode] 为空表示不在播放页，方向交回系统；跟随视频比例未知时锁住当前方向。
     * 与当前请求相同时不写，避免多余的方向变化。
     */
    private fun dispatchPlayerOrientation(mode: OrientationMode?) {
        val target = screenOrientationMode(
            mode = mode,
            videoAspectRatio = sessionOrientation.videoAspectRatio,
            rotationOverride = sessionOrientation.override,
            audioOnly = entryHasNoVideo || currentTargetHasNoVideo(),
        )
        if (target == requestedOrientation) return
        requestedOrientation = target
    }

    /**
     * 当前集有没有视频方向：扩展名是音频，或容器里没有视频轨。
     *
     * 没有视频方向的条目按屏幕方向档处理，不进入方向等待。列表未就绪时按有视频算。
     */
    private fun currentTargetHasNoVideo(): Boolean =
        playlist.getOrNull(currentIndex)?.let { mediaKindOf(it.name) == MediaKind.Audio } == true ||
            !surfacePresentation.targetHasVideo

    /**
     * 每次方向真正切换都写一条日志。
     *
     * 部分机型在这个回调里读到的显示方向仍是旧值：播放页的方向等待因此等不到对齐，
     * 由 2 秒上限兜底，多黑 2 秒后照常出画面。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screenOrientation = currentScreenOrientation()
        recordScreenOrientation(screenOrientation)
    }

    /** 记录一次屏幕方向切换；同一方向不重复记录。 */
    private fun recordScreenOrientation(orientation: ScreenOrientation) {
        if (orientation == lastScreenOrientation) return
        val previous = lastScreenOrientation
        lastScreenOrientation = orientation
        diagnostics.log {
            screenOrientationChangeLine(
                from = screenOrientationLabel(previous),
                to = screenOrientationLabel(orientation),
            )
        }
    }

    /**
     * 屏幕方向在诊断日志里的文案：按四个方向分别记录。[ScreenOrientation.System] 不写方向日志，
     * 该分支只为枚举完整。
     */
    private fun screenOrientationLabel(orientation: ScreenOrientation): String = when (orientation) {
        ScreenOrientation.Portrait -> "竖屏"
        ScreenOrientation.ReversePortrait -> "反向竖屏"
        ScreenOrientation.Landscape -> "横屏"
        ScreenOrientation.ReverseLandscape -> "反向横屏"
        ScreenOrientation.System -> "系统"
    }

    private fun applyImmersive(immersive: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        if (isFinishing) diagnostics.logSync("应用退出")
        surfaceSession.release()
        releaseNetworkStreams()
        stopPlaybackService()
        super.onDestroy()
    }

    private companion object {
        const val NO_MEDIA = -1

        /** 等待接手兜底的推进间隔。 */
        const val ENTRY_HANDOVER_TICK_MILLIS = 250L
    }
}
