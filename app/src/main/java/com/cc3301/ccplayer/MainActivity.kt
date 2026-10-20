package com.cc3301.ccplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.network.DataStoreBookmarkStore
import com.cc3301.ccplayer.network.EncryptedCredentialStore
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.network.RoutingMediaReads
import com.cc3301.ccplayer.network.RoutingNetworkStreams
import com.cc3301.ccplayer.network.SourceProtocol
import com.cc3301.ccplayer.network.withPlaybackLocation
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
import com.cc3301.ccplayer.ui.PlayerLevelControls
import com.cc3301.ccplayer.ui.PlayerScreen
import com.cc3301.ccplayer.ui.ScreenOrientation
import com.cc3301.ccplayer.ui.SettingsScreen
import com.cc3301.ccplayer.ui.SettingsViewModel
import com.cc3301.ccplayer.ui.SourceListScreen
import com.cc3301.ccplayer.ui.SubtitleMenuItem
import com.cc3301.ccplayer.ui.mediaFailureText
import com.cc3301.ccplayer.ui.resolvedOrientation
import com.cc3301.ccplayer.ui.theme.CcPlayerTheme
import com.cc3301.ccplayer.webdav.OkHttpWebDavTransport
import com.cc3301.ccplayer.webdav.WebDavAddress
import com.cc3301.ccplayer.webdav.WebDavLocator
import com.cc3301.ccplayer.webdav.WebDavMediaReads
import com.cc3301.ccplayer.webdav.WebDavStreams
import kotlinx.coroutines.CancellationException
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
                launch { binder.controller.subtitleTracks.collect { subtitleTracks = it } }
                launch { binder.controller.externalSubtitles.collect { externalSubtitles = it } }
                launch { binder.controller.subtitleSelection.collect { subtitleSelection = it } }
                launch { settings.autoPlayNext.collect { binder.controller.setAutoPlayNext(it) } }
                launch { settings.resumeMode.collect { binder.controller.setResumeMode(it) } }
                launch { settings.decodeMode.collect { binder.controller.setDecodeMode(it) } }
            }
            surface?.let { binder.engine.attachSurface(it) }
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
                LaunchedEffect(playerVisible, orientationMode, videoAspectRatio) {
                    applyOrientation(
                        if (playerVisible) resolvedOrientation(orientationMode, videoAspectRatio)
                        else ScreenOrientation.System,
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
                        BackHandler { closePlayer() }
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
                            subtitleTracks = subtitleTracks,
                            externalSubtitles = externalSubtitles,
                            subtitleSelection = subtitleSelection,
                            onTogglePlayPause = { playback?.controller?.togglePlayPause() },
                            onPrevious = { playback?.controller?.previous() },
                            onNext = { playback?.controller?.next() },
                            onSeekPreview = { playback?.controller?.updateSeekPreview(it) },
                            onSeekCommit = { playback?.controller?.commitSeekPreview() },
                            onSeekBy = { seekBy(it) },
                            onPlaylistSelect = { playback?.controller?.playAt(it) },
                            onSelectDecodeMode = { playback?.controller?.setDecodeMode(it) },
                            onSelectLoopMode = { playback?.controller?.setLoopMode(it) },
                            onSelectOrientationMode = { mode ->
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
                            onClose = { closePlayer() },
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
                                    openEntry(entry, library.state.value.entries)
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

    /**
     * 本地文件直接把位置交给播放核心；网络文件先试连一次，再给列表里的条目配上回环地址，
     * 位置本身不变，进度记录恒以它作键；上一集/下一集也就能照常按名称顺序播。
     * 两份列表都带上当前目录的原始条目，供外挂字幕匹配。
     */
    private fun openEntry(entry: MediaFile, entries: List<MediaFile>) {
        lifecycleScope.launch {
            val plainEntries = library.directoryEntries()
            if (entry.source != MediaSource.Network) {
                openMedia(entry, entries, plainEntries)
                return@launch
            }
            try {
                networkStreams().probe(entry.location)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: MediaSourceException) {
                showFailure(failure.failure)
                return@launch
            } catch (failure: Exception) {
                showFailure(MediaFailure.Unknown)
                return@launch
            }
            val streams = networkStreams()
            openMedia(
                entry.withPlaybackLocation(streams),
                entries.map { it.withPlaybackLocation(streams) },
                plainEntries.map { it.withPlaybackLocation(streams) },
            )
        }
    }

    /** 起播前连不上时用提示条告知，不进播放页。 */
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

    /** mpv 直接吃 content:// URI：ffmpeg 的 android_content 协议经应用 ContentResolver 读文件。 */
    private fun openMedia(current: MediaFile, files: List<MediaFile>, directoryEntries: List<MediaFile>) {
        pendingPlaylist = files
        pendingMedia = current
        pendingDirectoryEntries = directoryEntries
        playerVisible = true
        requestNotificationPermission()
        if (!bound) {
            val intent = Intent(this, PlaybackService::class.java)
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, connection, BIND_AUTO_CREATE)
            bound = true
        }
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
    }

    override fun onStart() {
        super.onStart()
        playback?.setAppInForeground(true)
        surface?.let { playback?.engine?.attachSurface(it) }
    }

    override fun onSurfaceReady(surface: Surface) {
        this.surface = surface
        playback?.engine?.attachSurface(surface)
        startPendingPlayback()
    }

    override fun onSurfaceResized(width: Int, height: Int) {
        playback?.engine?.setSurfaceSize(width, height)
    }

    override fun onSurfaceReleased() {
        if (surface == null) return
        surface = null
        playback?.engine?.detachSurface()
    }

    private fun closePlayer() {
        playback?.controller?.stop()
        onSurfaceReleased()
        pendingPlaylist = emptyList()
        pendingMedia = null
        pendingDirectoryEntries = emptyList()
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
