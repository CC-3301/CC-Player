package com.cc3301.ccplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackService
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.SleepTimerOption
import com.cc3301.ccplayer.playback.SleepTimerState
import com.cc3301.ccplayer.playback.SubtitleSelection
import com.cc3301.ccplayer.playback.SubtitleTrack
import com.cc3301.ccplayer.playback.SurfaceSession
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.smb.SmbJConnector
import com.cc3301.ccplayer.smb.SmbLocation
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbStreams
import com.cc3301.ccplayer.ui.AppScreen
import com.cc3301.ccplayer.ui.HomeBackResult
import com.cc3301.ccplayer.ui.HomeScreen
import com.cc3301.ccplayer.ui.LibraryBackAction
import com.cc3301.ccplayer.ui.LibraryScreen
import com.cc3301.ccplayer.ui.LibraryViewModel
import com.cc3301.ccplayer.ui.MediaVolumeControl
import com.cc3301.ccplayer.ui.MpvSurfaceView
import com.cc3301.ccplayer.ui.NavigationViewModel
import com.cc3301.ccplayer.ui.PendingPlayback
import com.cc3301.ccplayer.ui.PlaybackEntryOpener
import com.cc3301.ccplayer.ui.PlaybackEntryState
import com.cc3301.ccplayer.ui.PlayerLevelControls
import com.cc3301.ccplayer.ui.PlayerScreen
import com.cc3301.ccplayer.ui.PlayerSessionOrientation
import com.cc3301.ccplayer.ui.ScreenOrientation
import com.cc3301.ccplayer.ui.SettingsScreen
import com.cc3301.ccplayer.ui.SettingsViewModel
import com.cc3301.ccplayer.ui.SourceListScreen
import com.cc3301.ccplayer.ui.SubtitleMenuItem
import com.cc3301.ccplayer.ui.mediaFailureText
import com.cc3301.ccplayer.ui.relativeSeekTarget
import com.cc3301.ccplayer.ui.sensorScreenOrientation
import com.cc3301.ccplayer.ui.theme.CcPlayerTheme
import com.cc3301.ccplayer.webdav.OkHttpWebDavTransport
import com.cc3301.ccplayer.webdav.WebDavAddress
import com.cc3301.ccplayer.webdav.WebDavLocator
import com.cc3301.ccplayer.webdav.WebDavStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainActivity : ComponentActivity(), MpvSurfaceView.Callback {

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

    /** Surface 的挂载、分离与回前台重建都由会话持有，Activity 只转发事件。 */
    private val surfaceSession by lazy {
        SurfaceSession(
            attach = { surface ->
                playback?.engine?.let {
                    it.attachSurface(surface)
                    true
                } ?: false
            },
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

    /** 退后台时等待抓帧的分离任务；新的生命周期切换会取消旧任务。 */
    private var backgroundDetachJob: Job? = null

    private var playerVisible by mutableStateOf(false)

    /** 本次播放的转屏结果；为空时按设置判定，退出播放页即清除。 */
    private val sessionOrientation = PlayerSessionOrientation()

    /** 最近的设置方向档位；传感器回调在组合之外判定，需要即时读到。 */
    private var playerOrientationMode = SettingsRepository.DEFAULT_ORIENTATION_MODE

    /** 最近一次传感器角度；为空表示还没收到或设备平放无法判定，方向保持不动。 */
    private var sensorAngle: Int? = null

    /** 已施加的播放页方向，用于方向迟滞；进入播放页时回到系统朝向。 */
    private var appliedScreenOrientation = ScreenOrientation.System

    /** 上一次记录过的屏幕方向；方向真正变化时据此写一条诊断日志。 */
    private var lastScreenOrientation = ScreenOrientation.Portrait
    private var playbackState by mutableStateOf<PlaybackState>(PlaybackState.Idle)
    private var progress by mutableStateOf(PlaybackProgress())
    private var seekPreviewMillis by mutableStateOf<Long?>(null)
    private var playlist by mutableStateOf<List<MediaFile>>(emptyList())
    private var currentIndex by mutableStateOf(NO_MEDIA)
    private var decodeMode by mutableStateOf(DecodeMode.Hardware)
    private var loopMode by mutableStateOf(LoopMode.Sequential)
    private var sleepTimerState by mutableStateOf(SleepTimerState.Off)
    private var sleepTimerOption by mutableStateOf<SleepTimerOption?>(null)
    private var videoAspectRatio by mutableStateOf<Double?>(null)
    private var switching by mutableStateOf(false)
    private var restoring by mutableStateOf(false)
    private var buffering by mutableStateOf(false)
    private var holdBlack by mutableStateOf(false)

    /** 浏览页进入播放页的准备态与首次加载门禁。 */
    private val playbackEntry = PlaybackEntryState()

    /** 点开媒体：视频先进入播放页，音频等媒体准备完成后进入。 */
    private val playbackEntryOpener by lazy {
        PlaybackEntryOpener(
            scope = lifecycleScope,
            directoryEntries = { library.directoryEntries() },
            networkStreams = { networkStreams() },
            onEnterPlayer = ::enterPlayer,
            onStartPlayback = ::openMedia,
            onShowFailure = ::showFailure,
            onLeavePlayer = ::closePlayer,
            diagnostics = diagnostics,
        )
    }

    /** 切集时冻结的原视频画面，覆盖在视频 Surface 上直到目标画面就绪。 */
    private var freezeFrame by mutableStateOf<Bitmap?>(null)

    /** 切集抓帧仍在后台执行：原画面还在 Surface 上，不铺黑底。 */
    private var freezeFramePending by mutableStateOf(false)

    /** 暂停态退后台前缓存的画面，回前台先盖上，播放核心重新出帧后撤掉。 */
    private var backgroundRestoreFrame by mutableStateOf<Bitmap?>(null)
    private var subtitleTracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
    private var externalSubtitles by mutableStateOf<List<MediaFile>>(emptyList())
    private var subtitleSelection by mutableStateOf<SubtitleSelection>(SubtitleSelection.Off)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playback = binder
            stateCollection = lifecycleScope.launch {
                launch { binder.controller.state.collect { playbackState = it } }
                launch { binder.controller.progress.collect { progress = it } }
                launch { binder.controller.seekPreviewMillis.collect { seekPreviewMillis = it } }
                launch { binder.controller.playlist.collect { playlist = it } }
                launch { binder.controller.currentIndex.collect { currentIndex = it } }
                launch { binder.controller.decodeMode.collect { decodeMode = it } }
                launch { binder.controller.loopMode.collect { loopMode = it } }
                launch { binder.controller.sleepTimerState.collect { sleepTimerState = it } }
                launch { binder.controller.sleepTimerOption.collect { sleepTimerOption = it } }
                launch { binder.controller.videoAspectRatio.collect { videoAspectRatio = it } }
                launch { binder.controller.switching.collect { switching = it } }
                launch { binder.controller.restoring.collect { restoring = it } }
                launch { binder.controller.buffering.collect { buffering = it } }
                launch { binder.controller.holdBlack.collect { holdBlack = it } }
                launch { binder.controller.freezeFrame.collect { freezeFrame = it } }
                launch { binder.controller.freezeFramePending.collect { freezeFramePending = it } }
                launch { binder.controller.backgroundRestoreFrame.collect { backgroundRestoreFrame = it } }
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
        lastScreenOrientation = currentScreenOrientation()
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
                // 缓冲指示交给播放控制层后撤掉准备态；首次加载就绪或转入切集时解除失败退回。
                LaunchedEffect(playbackState, buffering, switching) {
                    playbackEntry.onPlaybackUpdate(playbackState, buffering, switching)
                }
                // 播放页由传感器判定四向方向；离开播放页把方向交回系统。
                LaunchedEffect(playerVisible, orientationMode, videoAspectRatio, sessionOrientation.override) {
                    playerOrientationMode = orientationMode
                    if (playerVisible) {
                        applyPlayerOrientation()
                    } else {
                        applyOrientation(ScreenOrientation.System)
                    }
                }
                // 传感器只在播放页注册，离开播放页注销。
                DisposableEffect(playerVisible) {
                    if (!playerVisible) return@DisposableEffect onDispose { }
                    val listener = object : OrientationEventListener(this@MainActivity, SensorManager.SENSOR_DELAY_UI) {
                        override fun onOrientationChanged(orientation: Int) {
                            if (!playerVisible) return
                            sensorAngle = orientation.takeIf { it != OrientationEventListener.ORIENTATION_UNKNOWN }
                            applyPlayerOrientation()
                        }
                    }
                    if (listener.canDetectOrientation()) listener.enable()
                    onDispose {
                        listener.disable()
                        sensorAngle = null
                    }
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) addRoot(uri)
                }

                val levelControls = remember {
                    PlayerLevelControls(volume = MediaVolumeControl(this@MainActivity))
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
                            playlist = playlist,
                            currentIndex = currentIndex,
                            decodeMode = decodeMode,
                            loopMode = loopMode,
                            orientationMode = orientationMode,
                            autoPlayNext = autoPlayNext,
                            levelControls = levelControls,
                            surfaceCallback = this@MainActivity,
                            videoAspectRatio = videoAspectRatio,
                            switching = switching,
                            restoring = restoring,
                            buffering = playbackEntry.showingBuffering(buffering),
                            initialLoading = playbackEntry.initialLoading(playbackState, switching),
                            freezeFrame = freezeFrame,
                            freezeFramePending = freezeFramePending,
                            backgroundRestoreFrame = backgroundRestoreFrame,
                            holdBlack = holdBlack,
                            subtitleTracks = subtitleTracks,
                            externalSubtitles = externalSubtitles,
                            subtitleSelection = subtitleSelection,
                            onTogglePlayPause = { playback?.controller?.togglePlayPause() },
                            onPrevious = { playback?.controller?.previous() },
                            onNext = { playback?.controller?.next() },
                            onSeekPreview = { playback?.controller?.updateSeekPreview(it) },
                            onSeekCommit = { playback?.controller?.commitSeekPreview() },
                            onSeekBy = { seekBy(it) },
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
                            onClose = { handlePlayerBack() },
                        )
                    }

                    navState.screen == AppScreen.Settings -> {
                        BackHandler { navigation.closeSettings() }
                        SettingsScreen(
                            state = settingsState,
                            onSetAutoPlayNext = settingsViewModel::setAutoPlayNext,
                            onSetBackgroundPlayback = settingsViewModel::setBackgroundPlayback,
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
                                    playbackEntryOpener.open(
                                        entry = entry,
                                        entries = libraryState.entries,
                                        enterBeforePreparing = mediaKindOf(entry.name) == MediaKind.Video,
                                    )
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
        controller.updateSeekPreview(target.targetMillis)
        controller.commitSeekPreview()
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

    /** 进入播放页；[preparing] 为真时先显示黑屏与缓冲指示，准备在后台继续。 */
    private fun enterPlayer(preparing: Boolean) {
        sessionOrientation.beginPlayback()
        appliedScreenOrientation = ScreenOrientation.System
        playerVisible = true
        playbackEntry.enter(preparing)
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
        val webDavStreams = WebDavStreams(OkHttpWebDavTransport(), WebDavLocator(sources))
        return RoutingNetworkStreams(
            mapOf(
                SmbLocation.SCHEME to SmbStreams(SmbJConnector(), SmbLocator(sources)),
                WebDavAddress.SCHEME_HTTP to webDavStreams,
                WebDavAddress.SCHEME_HTTPS to webDavStreams,
            ),
        )
    }

    /** 后台准备完成：把播放列表与字幕目录交给播放控制层。 */
    private fun openMedia(current: MediaFile, files: List<MediaFile>, directoryEntries: List<MediaFile>) {
        pendingPlayback.set(current, files, directoryEntries)
        startPendingPlayback()
    }

    /** 引擎就绪且 Surface 已就位时才开始播放。 */
    private fun startPendingPlayback() {
        val binder = playback ?: return
        pendingPlayback.start(canStart = surfaceSession.hasSurface) { current, files, directoryEntries ->
            binder.controller.openPlaylist(files, current, directoryEntries)
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

    /** 退到后台停止画面渲染；是否暂停由播放服务按后台播放设置决定。 */
    override fun onStop() {
        super.onStop()
        diagnostics.log("应用退到后台")
        val binder = playback
        binder?.setAppInForeground(false)
        surfaceSession.onActivityStopped()
        // 后台上报可能已把播放暂停；抓帧在后台执行，完成后由 Surface 会话分离。
        detachAfterBackgroundCapture { !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    }

    /**
     * 后台离开时先抓帧再分离 Surface。抓帧在后台调度执行，最多等待
     * [BACKGROUND_CAPTURE_DETACH_WAIT_MILLIS]；超时放弃结果并照常分离，避免挂住已销毁的 Surface。
     * 回到前台后同样放弃本周期结果，不再分离。
     */
    private fun detachAfterBackgroundCapture(shouldDetach: () -> Boolean) {
        val binder = playback ?: return
        backgroundDetachJob?.cancel()
        val capture = binder.controller.beginBackgroundCapture()
        backgroundDetachJob = lifecycleScope.launch {
            awaitCaptureThenDetach(
                capture = capture,
                timeoutMillis = BACKGROUND_CAPTURE_DETACH_WAIT_MILLIS,
                shouldDetach = shouldDetach,
                abandonCapture = binder.controller::abandonBackgroundCapture,
                detachSurface = surfaceSession::detach,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        diagnostics.log("应用进入前台")
        playback?.setAppInForeground(true)
        // 复用保存的 Surface：回前台不依赖 surfaceCreated 再次触发。
        surfaceSession.onActivityStarted()
    }

    override fun onSurfaceReady(surface: Surface) {
        surfaceSession.onSurfaceCreated(surface)
        startPendingPlayback()
    }

    override fun onSurfaceResized(width: Int, height: Int) {
        surfaceSession.onSurfaceChanged(width, height)
    }

    /** 返回先尝试取消切集；没有切集才退出播放页。 */
    private fun handlePlayerBack() {
        if (playback?.controller?.cancelSwitch() != true) closePlayer()
    }

    override fun onSurfaceReleased() {
        surfaceSession.onSurfaceDestroyed()
        // 退后台时 Surface 可能先于 onStop 销毁，在这里补抓；转屏或关闭播放页不抓。
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            detachAfterBackgroundCapture { !surfaceSession.hasSurface }
        } else {
            val binder = playback ?: return
            backgroundDetachJob?.cancel()
            binder.controller.abandonBackgroundCapture()
            surfaceSession.detach()
        }
    }

    private fun closePlayer() {
        playbackEntryOpener.cancel()
        playback?.controller?.stop()
        onSurfaceReleased()
        pendingPlayback.clear()
        playbackEntry.reset()
        // 先恢复系统栏与方向，再切回浏览页，避免 insets 变化让浏览页布局跳动。
        restoreBrowserSystemUi()
        playerVisible = false
        playbackState = PlaybackState.Idle
        progress = PlaybackProgress()
        seekPreviewMillis = null
        playlist = emptyList()
        currentIndex = NO_MEDIA
        decodeMode = DecodeMode.Hardware
        loopMode = LoopMode.Sequential
        sleepTimerState = SleepTimerState.Off
        sleepTimerOption = null
        videoAspectRatio = null
        switching = false
        restoring = false
        buffering = false
        holdBlack = false
        freezeFrame = null
        freezeFramePending = false
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
     * 退出播放页：先同步恢复系统栏与方向，保证系统栏在页面切换前出现；
     * 状态变化后 [LaunchedEffect] 会按非播放状态再兜底应用一次，重复调用无副作用。
     */
    private fun restoreBrowserSystemUi() {
        applyImmersive(false)
        sessionOrientation.endPlayback()
        applyOrientation(ScreenOrientation.System)
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
     * 播放页按传感器角度与期望族施加四向朝向；方向真正变化时才写一条诊断日志。
     */
    private fun applyPlayerOrientation() {
        val requested = sessionOrientation.resolve(playerOrientationMode, videoAspectRatio)
        val target = sensorScreenOrientation(requested, sensorAngle, appliedScreenOrientation)
        if (target == appliedScreenOrientation) return
        appliedScreenOrientation = target
        applyOrientation(target)
        recordScreenOrientation(target)
    }

    /** 每次方向真正切换都写一条日志。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recordScreenOrientation(currentScreenOrientation())
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

    /** 屏幕方向在诊断日志里的文案：按四个方向分别记录。 */
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

    /** 播放页按传感器判定施加朝向；离开播放页时交回系统自动旋转设置。 */
    private fun applyOrientation(orientation: ScreenOrientation) {
        requestedOrientation = when (orientation) {
            ScreenOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            ScreenOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
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

        /** 后台暂停态抓帧的等待上限；超时放弃结果并先分离 Surface，回前台由 seek 逼出画面。 */
        const val BACKGROUND_CAPTURE_DETACH_WAIT_MILLIS = 2_000L
    }
}

/**
 * 等待后台抓帧后决定是否分离 Surface：[capture] 为空或超时都放弃本周期结果并照常分离；
 * 已回到前台时只放弃结果，不分离。
 */
internal suspend fun awaitCaptureThenDetach(
    capture: Job?,
    timeoutMillis: Long,
    shouldDetach: () -> Boolean,
    abandonCapture: () -> Unit,
    detachSurface: () -> Unit,
) {
    val captured = capture != null && withTimeoutOrNull(timeoutMillis) {
        capture.join()
        true
    } == true
    if (!shouldDetach()) {
        abandonCapture()
        return
    }
    // 超时或没有抓帧任务都放弃本周期结果，照常分离 Surface。
    if (!captured) abandonCapture()
    detachSurface()
}
