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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
import androidx.lifecycle.lifecycleScope
import com.cc3301.ccplayer.library.CachedMediaPreviewSource
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaPreviewSource
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.DataStoreBookmarkStore
import com.cc3301.ccplayer.network.EncryptedCredentialStore
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackService
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.next
import com.cc3301.ccplayer.smb.SmbJConnector
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbStreams
import com.cc3301.ccplayer.ui.LibraryScreen
import com.cc3301.ccplayer.ui.LibraryViewModel
import com.cc3301.ccplayer.ui.MediaVolumeControl
import com.cc3301.ccplayer.ui.MpvSurfaceView
import com.cc3301.ccplayer.ui.PlayerLevelControls
import com.cc3301.ccplayer.ui.PlayerScreen
import com.cc3301.ccplayer.ui.ScreenOrientation
import com.cc3301.ccplayer.ui.WindowBrightnessControl
import com.cc3301.ccplayer.ui.mediaFailureText
import com.cc3301.ccplayer.ui.resolvedOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), MpvSurfaceView.Callback {

    private val library: LibraryViewModel by viewModels { LibraryViewModel.factory(applicationContext) }

    private val mediaPreview: MediaPreviewSource by lazy { CachedMediaPreviewSource.create(applicationContext) }

    private val settings: SettingsRepository by lazy { SettingsRepository.create(applicationContext) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var playback: PlaybackService.LocalBinder? = null
    private var bound = false
    private var surface: Surface? = null
    private var pendingPlaylist: List<MediaFile> = emptyList()
    private var pendingMedia: MediaFile? = null
    private var stateCollection: Job? = null
    private var streams: SmbStreams? = null

    private var playerVisible by mutableStateOf(false)
    private var playbackState by mutableStateOf<PlaybackState>(PlaybackState.Idle)
    private var progress by mutableStateOf(PlaybackProgress())
    private var seekPreviewMillis by mutableStateOf<Long?>(null)
    private var playlist by mutableStateOf<List<MediaFile>>(emptyList())
    private var currentIndex by mutableStateOf(NO_MEDIA)
    private var decodeMode by mutableStateOf(DecodeMode.Hardware)
    private var loopMode by mutableStateOf(LoopMode.Sequential)
    private var videoAspectRatio by mutableStateOf<Double?>(null)

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
                launch { binder.controller.videoAspectRatio.collect { videoAspectRatio = it } }
                launch { settings.autoPlayNext.collect { binder.controller.setAutoPlayNext(it) } }
                launch { settings.resumeMode.collect { binder.controller.setResumeMode(it) } }
                launch {
                    combine(binder.controller.playlist, binder.controller.currentIndex) { playlist, index ->
                        playlist.getOrNull(index)
                    }.collect { media -> if (media != null) settings.setLastPlayedLocation(media.location) }
                }
            }
            surface?.let { binder.engine.attachSurface(it) }
            startPendingPlayback()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val libraryState by library.state.collectAsState()
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
                    PlayerLevelControls(
                        brightness = WindowBrightnessControl(this@MainActivity),
                        volume = MediaVolumeControl(this@MainActivity),
                    )
                }

                if (playerVisible) {
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
                        onTogglePlayPause = { playback?.controller?.togglePlayPause() },
                        onPrevious = { playback?.controller?.previous() },
                        onNext = { playback?.controller?.next() },
                        onSeekPreview = { playback?.controller?.updateSeekPreview(it) },
                        onSeekCommit = { playback?.controller?.commitSeekPreview() },
                        onSeekBy = { seekBy(it) },
                        onPlaylistSelect = { playback?.controller?.playAt(it) },
                        onToggleDecodeMode = { playback?.controller?.toggleDecodeMode() },
                        onToggleLoopMode = { playback?.controller?.cycleLoopMode() },
                        onCycleOrientationMode = {
                            lifecycleScope.launch { settings.setOrientationMode(orientationMode.next()) }
                        },
                        onToggleAutoPlayNext = {
                            lifecycleScope.launch { settings.setAutoPlayNext(!autoPlayNext) }
                        },
                        onClose = { closePlayer() },
                    )
                } else {
                    BackHandler(enabled = libraryState.stack.isNotEmpty()) { library.navigateUp() }
                    LibraryScreen(
                        state = libraryState,
                        mediaPreview = mediaPreview,
                        onPickRoot = { picker.launch(null) },
                        onOpenNetworkSourceForm = library::openNetworkSourceForm,
                        onDismissNetworkSourceForm = library::dismissNetworkSourceForm,
                        onAddNetworkSource = library::addNetworkSource,
                        onOpenEntry = { entry ->
                            if (entry.isDirectory) {
                                library.enter(entry)
                            } else {
                                openEntry(entry, library.state.value.entries)
                            }
                        },
                        onNavigateUp = library::navigateUp,
                        onRetry = library::retry,
                        onSortOrderChange = library::setSortOrder,
                        onViewModeChange = library::setViewMode,
                        onGridColumnsChange = library::setGridColumns,
                    )
                }
            }
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
        library.addRoot(uri.toString())
    }

    /**
     * 本地文件直接把位置交给播放核心；网络文件先试连一次，再把整个播放列表换成回环地址，
     * 上一集/下一集也就能照常按名称顺序播。
     */
    private fun openEntry(entry: MediaFile, entries: List<MediaFile>) {
        if (entry.source != MediaSource.Network) {
            openMedia(entry, entries)
            return
        }
        lifecycleScope.launch {
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
            val playable = entries.map(::playableEntry)
            openMedia(playableEntry(entry), playable)
        }
    }

    /** 起播前连不上时用提示条告知，不进播放页。 */
    private fun showFailure(failure: MediaFailure) {
        Toast.makeText(this, getString(mediaFailureText(failure)), Toast.LENGTH_LONG).show()
    }

    /** 网络位置换成回环地址；本地位置与目录原样保留。 */
    private fun playableEntry(entry: MediaFile): MediaFile =
        if (entry.isDirectory || entry.source != MediaSource.Network) {
            entry
        } else {
            entry.copy(location = networkStreams().urlFor(entry.location))
        }

    /** 回环代理与 SMB 会话按需建立，关播放页时一并释放。 */
    private fun networkStreams(): SmbStreams = streams ?: createNetworkStreams().also { streams = it }

    private fun createNetworkStreams(): SmbStreams {
        val sources = NetworkSourceRepository(
            store = DataStoreBookmarkStore.create(applicationContext),
            credentials = EncryptedCredentialStore.create(applicationContext),
        )
        return SmbStreams(SmbJConnector(), SmbLocator(sources))
    }

    /** mpv 直接吃 content:// URI：ffmpeg 的 android_content 协议经应用 ContentResolver 读文件。 */
    private fun openMedia(current: MediaFile, files: List<MediaFile>) {
        pendingPlaylist = files
        pendingMedia = current
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
        binder.controller.openPlaylist(pendingPlaylist, current)
    }

    /** 通知栏要能显示就得有这个权限（Android 13 起）。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 退到后台停止画面渲染，音频继续；回前台接回同一块 Surface，媒体不重新打开。 */
    override fun onStop() {
        super.onStop()
        playback?.engine?.detachSurface()
    }

    override fun onStart() {
        super.onStart()
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
        playerVisible = false
        playbackState = PlaybackState.Idle
        progress = PlaybackProgress()
        seekPreviewMillis = null
        playlist = emptyList()
        currentIndex = NO_MEDIA
        decodeMode = DecodeMode.Hardware
        loopMode = LoopMode.Sequential
        videoAspectRatio = null
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
