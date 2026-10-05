package com.cc3301.ccplayer

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
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
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cc3301.ccplayer.library.CachedMediaPreviewSource
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaPreviewSource
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.playback.LoopMode
import com.cc3301.ccplayer.playback.PlaybackProgress
import com.cc3301.ccplayer.playback.PlaybackService
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.ui.LibraryScreen
import com.cc3301.ccplayer.ui.LibraryViewModel
import com.cc3301.ccplayer.ui.MpvSurfaceView
import com.cc3301.ccplayer.ui.PlayerScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), MpvSurfaceView.Callback {

    private val library: LibraryViewModel by viewModels { LibraryViewModel.factory(applicationContext) }

    private val mediaPreview: MediaPreviewSource by lazy { CachedMediaPreviewSource.create(applicationContext) }

    private val settings: SettingsRepository by lazy { SettingsRepository.create(applicationContext) }

    private var playback: PlaybackService.LocalBinder? = null
    private var bound = false
    private var surface: Surface? = null
    private var pendingPlaylist: List<MediaFile> = emptyList()
    private var pendingMedia: MediaFile? = null
    private var stateCollection: Job? = null

    private var playerVisible by mutableStateOf(false)
    private var playbackState by mutableStateOf<PlaybackState>(PlaybackState.Idle)
    private var progress by mutableStateOf(PlaybackProgress())
    private var seekPreviewMillis by mutableStateOf<Long?>(null)
    private var playlist by mutableStateOf<List<MediaFile>>(emptyList())
    private var currentIndex by mutableStateOf(NO_MEDIA)
    private var decodeMode by mutableStateOf(DecodeMode.Hardware)
    private var loopMode by mutableStateOf(LoopMode.Sequential)

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
                launch { settings.autoPlayNext.collect { binder.controller.setAutoPlayNext(it) } }
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
                LaunchedEffect(playerVisible) { applyImmersive(playerVisible) }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) addRoot(uri)
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
                        autoPlayNext = autoPlayNext,
                        surfaceCallback = this@MainActivity,
                        onTogglePlayPause = { playback?.controller?.togglePlayPause() },
                        onPrevious = { playback?.controller?.previous() },
                        onNext = { playback?.controller?.next() },
                        onSeekPreview = { playback?.controller?.updateSeekPreview(it) },
                        onSeekCommit = { playback?.controller?.commitSeekPreview() },
                        onPlaylistSelect = { playback?.controller?.playAt(it) },
                        onToggleDecodeMode = { playback?.controller?.toggleDecodeMode() },
                        onToggleLoopMode = { playback?.controller?.cycleLoopMode() },
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
                        onOpenEntry = { entry ->
                            if (entry.isDirectory) {
                                library.enter(entry)
                            } else {
                                openMedia(entry, library.state.value.entries)
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

    /** 取持久读授权：目录选定后重启仍可读，不需要任何存储权限。 */
    private fun addRoot(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        library.addRoot(uri.toString())
    }

    /** mpv 直接吃 content:// URI：ffmpeg 的 android_content 协议经应用 ContentResolver 读文件。 */
    private fun openMedia(current: MediaFile, files: List<MediaFile>) {
        pendingPlaylist = files
        pendingMedia = current
        playerVisible = true
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
        stopPlaybackService()
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

    override fun onDestroy() {
        stopPlaybackService()
        super.onDestroy()
    }

    private companion object {
        const val NO_MEDIA = -1
    }
}
