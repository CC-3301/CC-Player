package com.cc3301.ccplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.cc3301.ccplayer.library.CachedMediaPreviewSource
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaPreviewSource
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.network.DataStoreBookmarkStore
import com.cc3301.ccplayer.network.EncryptedCredentialStore
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.network.RoutingMediaReads
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
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.smb.SmbJConnector
import com.cc3301.ccplayer.smb.SmbLocation
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbMediaReads
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
import com.cc3301.ccplayer.ui.PlaybackEntryOpener
import com.cc3301.ccplayer.ui.PlayerLevelControls
import com.cc3301.ccplayer.ui.PlayerScreen
import com.cc3301.ccplayer.ui.PlayerSessionOrientation
import com.cc3301.ccplayer.ui.ScreenOrientation
import com.cc3301.ccplayer.ui.SettingsScreen
import com.cc3301.ccplayer.ui.SettingsViewModel
import com.cc3301.ccplayer.ui.SourceListScreen
import com.cc3301.ccplayer.ui.SubtitleMenuItem
import com.cc3301.ccplayer.ui.mediaFailureText
import com.cc3301.ccplayer.ui.theme.CcPlayerTheme
import com.cc3301.ccplayer.webdav.OkHttpWebDavTransport
import com.cc3301.ccplayer.webdav.WebDavAddress
import com.cc3301.ccplayer.webdav.WebDavLocator
import com.cc3301.ccplayer.webdav.WebDavMediaReads
import com.cc3301.ccplayer.webdav.WebDavStreams
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), MpvSurfaceView.Callback {

    private val library: LibraryViewModel by viewModels { LibraryViewModel.factory(applicationContext) }

    private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModel.factory(applicationContext) }

    private val navigation: NavigationViewModel by viewModels { NavigationViewModel.factory() }

    private val mediaPreview: MediaPreviewSource by lazy { CachedMediaPreviewSource.create(applicationContext, createPreviewReads()) }

    private val settings: SettingsRepository by lazy { SettingsRepository.create(applicationContext) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var playback: PlaybackService.LocalBinder? = null
    private var bound = false
    private var surface: Surface? = null
    private var pendingPlaylist: List<MediaFile> = emptyList()
    private var pendingMedia: MediaFile? = null

    /** 当前目录的原始条目（含外挂字幕），起播时交给控制层做字幕匹配。 */
    private var pendingDirectoryEntries: List<MediaFile> = emptyList()
    private var stateCollection: Job? = null
    private var streams: NetworkStreams? = null

    private var playerVisible by mutableStateOf(false)

    /** 本次播放的转屏结果；为空时按设置判定，退出播放页即清除。 */
    private val sessionOrientation = PlayerSessionOrientation()
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
    private var buffering by mutableStateOf(false)

    /** 已进入播放页、正在后台解析目录与连接；此期间保持黑屏与缓冲提示。 */
    private var openingPlayback by mutableStateOf(false)

    /** 首次起播尚未就绪；此阶段加载失败直接退回列表。 */
    private var awaitingInitialPlayback by mutableStateOf(false)

    /** 点开媒体：先进入播放页，再在后台准备播放列表与网络连接。 */
    private val playbackEntryOpener by lazy {
        PlaybackEntryOpener(
            scope = lifecycleScope,
            directoryEntries = { library.directoryEntries() },
            networkStreams = { networkStreams() },
            onEnterPlayer = ::enterPlayer,
            onReady = { current, files, directoryEntries -> openMedia(current, files, directoryEntries) },
            onFailure = ::failOpeningPlayback,
        )
    }

    /** 切集时冻结的原视频画面，覆盖在视频 Surface 上直到目标首帧就绪。 */
    private var freezeFrame by mutableStateOf<Bitmap?>(null)
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
                launch { binder.controller.buffering.collect { buffering = it } }
                launch { binder.controller.freezeFrame.collect { freezeFrame = it } }
                launch {
                    binder.controller.playbackFailures.collect {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.player_playback_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        // 首次起播失败时没有可停留的画面，退回浏览页。
                        if (awaitingInitialPlayback) closePlayer()
                    }
                }
                launch { binder.controller.subtitleTracks.collect { subtitleTracks = it } }
                launch { binder.controller.externalSubtitles.collect { externalSubtitles = it } }
                launch { binder.controller.subtitleSelection.collect { subtitleSelection = it } }
                launch { settings.autoPlayNext.collect { binder.controller.setAutoPlayNext(it) } }
                launch { settings.resumeMode.collect { binder.controller.setResumeMode(it) } }
                launch { settings.decodeMode.collect { binder.controller.setDecodeMode(it) } }
            }
            surface?.let { binder.engine.attachSurface(it) }
            binder.controller.setRenderSurfaceAvailable(surface != null)
            binder.setAppInForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            startPendingPlayback()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                val autoPlayNext by settings.autoPlayNext.collectAsState(
                    initial = SettingsRepository.DEFAULT_AUTO_PLAY_NEXT,
                )
                val orientationMode by settings.orientationMode.collectAsState(
                    initial = SettingsRepository.DEFAULT_ORIENTATION_MODE,
                )
                LaunchedEffect(playerVisible) { applyImmersive(playerVisible) }
                // 后台准备完成后撤掉进入播放页时的临时缓冲提示，由播放控制层接管。
                LaunchedEffect(playerVisible, openingPlayback, buffering, playbackState, switching) {
                    if (playerVisible && openingPlayback &&
                        (buffering || playbackState == PlaybackState.Playing || playbackState == PlaybackState.Paused)
                    ) {
                        openingPlayback = false
                    }
                    if (!playerVisible || switching ||
                        playbackState == PlaybackState.Playing || playbackState == PlaybackState.Paused
                    ) {
                        awaitingInitialPlayback = false
                    }
                }
                LaunchedEffect(playerVisible, orientationMode, videoAspectRatio, sessionOrientation.override) {
                    applyOrientation(
                        if (playerVisible) {
                            sessionOrientation.resolve(orientationMode, videoAspectRatio)
                        } else {
                            ScreenOrientation.System
                        },
                    )
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) addRoot(uri)
                }

                val levelControls = remember {
                    PlayerLevelControls(volume = MediaVolumeControl(this@MainActivity))
                }

                when {
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
                            buffering = buffering || openingPlayback,
                            freezeFrame = freezeFrame,
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
                            onSetDecodeMode = settingsViewModel::setDecodeMode,
                            onSetOrientationMode = settingsViewModel::setOrientationMode,
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
                                library.openSource(source)
                                navigation.openLibrary()
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
                            when (navigation.backFromLibrary(libraryState.stack.size > 1)) {
                                LibraryBackAction.NavigateUp -> library.navigateUp()
                                LibraryBackAction.LeaveLibrary -> Unit
                            }
                        }
                        LibraryScreen(
                            state = libraryState,
                            mediaPreview = mediaPreview,
                            onOpenEntry = { entry ->
                                if (entry.isDirectory) {
                                    library.enter(entry)
                                } else {
                                    playbackEntryOpener.open(entry, libraryState.entries)
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

    /** 双击三分区的快退/快进：目标位置夹取在当前时长内；时长未知时不动作。 */
    private fun seekBy(deltaMillis: Long) {
        val controller = playback?.controller ?: return
        val duration = progress.durationMillis
        if (duration <= 0) return
        controller.updateSeekPreview((progress.positionMillis + deltaMillis).coerceIn(0L, duration))
        controller.commitSeekPreview()
    }

    /** 取持久读授权：目录选定后重启仍可读，不需要任何存储权限。 */
    private fun addRoot(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        library.addLocalRoot(uri.toString())
    }

    /** 点开媒体后立即进入播放页：黑屏与缓冲提示先出现，准备在后台继续。 */
    private fun enterPlayer() {
        sessionOrientation.beginPlayback()
        playerVisible = true
        openingPlayback = true
        awaitingInitialPlayback = true
        requestNotificationPermission()
        if (!bound) {
            val intent = Intent(this, PlaybackService::class.java)
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, connection, BIND_AUTO_CREATE)
            bound = true
        }
    }

    /** 后台准备失败：退回浏览页并提示来源原因。 */
    private fun failOpeningPlayback(failure: MediaFailure) {
        showFailure(failure)
        closePlayer()
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

    /** 预览读取直接走各协议，不建回环代理，也不参与播放代理的释放。 */
    private fun createPreviewReads(): NetworkMediaReads {
        val sources = NetworkSourceRepository(
            store = DataStoreBookmarkStore.create(applicationContext),
            credentials = EncryptedCredentialStore.create(applicationContext),
        )
        val webDavReads = WebDavMediaReads(OkHttpWebDavTransport(), WebDavLocator(sources))
        return RoutingMediaReads(
            mapOf(
                SmbLocation.SCHEME to SmbMediaReads(SmbJConnector(), SmbLocator(sources)),
                WebDavAddress.SCHEME_HTTP to webDavReads,
                WebDavAddress.SCHEME_HTTPS to webDavReads,
            ),
        )
    }

    /** 后台准备完成：把播放列表与字幕目录交给播放控制层。 */
    private fun openMedia(current: MediaFile, files: List<MediaFile>, directoryEntries: List<MediaFile>) {
        pendingPlaylist = files
        pendingMedia = current
        pendingDirectoryEntries = directoryEntries
        startPendingPlayback()
    }

    /** 引擎就绪且 Surface 已就位时才开始播放。 */
    private fun startPendingPlayback() {
        val current = pendingMedia ?: return
        val binder = playback ?: return
        if (surface == null) return
        pendingMedia = null
        binder.controller.openPlaylist(pendingPlaylist, current, pendingDirectoryEntries)
        // 播放可能在退后台之后才真正开始，开播后重新上报一次当前前后台。
        binder.setAppInForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
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
        playback?.setAppInForeground(false)
        playback?.engine?.detachSurface()
        playback?.controller?.setRenderSurfaceAvailable(false)
    }

    override fun onStart() {
        super.onStart()
        playback?.setAppInForeground(true)
        surface?.let {
            playback?.engine?.attachSurface(it)
            playback?.controller?.setRenderSurfaceAvailable(true)
        }
    }

    override fun onSurfaceReady(surface: Surface) {
        this.surface = surface
        playback?.engine?.attachSurface(surface)
        playback?.controller?.setRenderSurfaceAvailable(true)
        startPendingPlayback()
    }

    override fun onSurfaceResized(width: Int, height: Int) {
        playback?.engine?.setSurfaceSize(width, height)
    }

    /** 返回先尝试取消切集；没有切集才退出播放页。 */
    private fun handlePlayerBack() {
        if (playback?.controller?.cancelSwitch() != true) closePlayer()
    }

    override fun onSurfaceReleased() {
        if (surface == null) return
        surface = null
        playback?.engine?.detachSurface()
        playback?.controller?.setRenderSurfaceAvailable(false)
    }

    private fun closePlayer() {
        playbackEntryOpener.cancel()
        playback?.controller?.stop()
        onSurfaceReleased()
        pendingPlaylist = emptyList()
        pendingMedia = null
        pendingDirectoryEntries = emptyList()
        openingPlayback = false
        awaitingInitialPlayback = false
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
        buffering = false
        freezeFrame = null
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

    /** 当前屏幕的横竖状态，供转屏按钮从现状切换。 */
    private fun currentScreenOrientation(): ScreenOrientation =
        when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ScreenOrientation.Landscape
            else -> ScreenOrientation.Portrait
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

    /** 播放页按设置施加朝向；离开播放页时交回系统自动旋转设置。 */
    private fun applyOrientation(orientation: ScreenOrientation) {
        requestedOrientation = when (orientation) {
            ScreenOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onDestroy() {
        releaseNetworkStreams()
        stopPlaybackService()
        super.onDestroy()
    }

    private companion object {
        const val NO_MEDIA = -1
    }
}
