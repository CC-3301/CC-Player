package com.cc3301.ccplayer

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
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
import com.cc3301.ccplayer.playback.PlaybackService
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.smb.SmbJConnector
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbStreams
import com.cc3301.ccplayer.ui.LibraryScreen
import com.cc3301.ccplayer.ui.LibraryViewModel
import com.cc3301.ccplayer.ui.MpvSurfaceView
import com.cc3301.ccplayer.ui.PlayerScreen
import com.cc3301.ccplayer.ui.mediaFailureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), MpvSurfaceView.Callback {

    private val library: LibraryViewModel by viewModels { LibraryViewModel.factory(applicationContext) }

    private val mediaPreview: MediaPreviewSource by lazy { CachedMediaPreviewSource.create(applicationContext) }

    private var playback: PlaybackService.LocalBinder? = null
    private var bound = false
    private var surface: Surface? = null
    private var pendingUrl: String? = null
    private var stateCollection: Job? = null
    private var streams: SmbStreams? = null

    private var playerVisible by mutableStateOf(false)
    private var playbackState by mutableStateOf<PlaybackState>(PlaybackState.Idle)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playback = binder
            stateCollection = lifecycleScope.launch {
                binder.controller.state.collect { playbackState = it }
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
                LaunchedEffect(playerVisible) { applyImmersive(playerVisible) }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) addRoot(uri)
                }

                if (playerVisible) {
                    BackHandler { closePlayer() }
                    PlayerScreen(
                        state = playbackState,
                        surfaceCallback = this@MainActivity,
                        onTogglePlayPause = { playback?.controller?.togglePlayPause() },
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
                            if (entry.isDirectory) library.enter(entry) else openEntry(entry)
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

    /**
     * 本地文件直接把位置交给播放核心；网络文件先经回环代理换成 http://127.0.0.1 上的地址。
     */
    private fun openEntry(entry: MediaFile) {
        if (entry.source != MediaSource.Network) {
            openMedia(entry.location)
            return
        }
        lifecycleScope.launch {
            val url = try {
                networkStreams().urlFor(entry.location)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: MediaSourceException) {
                showFailure(failure.failure)
                return@launch
            } catch (failure: Exception) {
                showFailure(MediaFailure.Unknown)
                return@launch
            }
            if (url == null) showFailure(MediaFailure.Unknown) else openMedia(url)
        }
    }

    /** 取不到流时用提示条告知，不进播放页。 */
    private fun showFailure(failure: MediaFailure) {
        Toast.makeText(this, getString(mediaFailureText(failure)), Toast.LENGTH_LONG).show()
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
    private fun openMedia(location: String) {
        pendingUrl = location
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
        val url = pendingUrl ?: return
        val binder = playback ?: return
        if (surface == null) return
        pendingUrl = null
        binder.controller.open(url)
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
        pendingUrl = null
        playerVisible = false
        playbackState = PlaybackState.Idle
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

    override fun onDestroy() {
        releaseNetworkStreams()
        stopPlaybackService()
        super.onDestroy()
    }
}
