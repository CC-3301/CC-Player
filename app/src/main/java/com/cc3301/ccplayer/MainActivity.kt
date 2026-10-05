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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cc3301.ccplayer.playback.PlaybackService
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.ui.HomeScreen
import com.cc3301.ccplayer.ui.MpvSurfaceView
import com.cc3301.ccplayer.ui.PlayerScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), MpvSurfaceView.Callback {

    private var playback: PlaybackService.LocalBinder? = null
    private var bound = false
    private var surface: Surface? = null
    private var pendingUrl: String? = null
    private var stateCollection: Job? = null

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
                LaunchedEffect(playerVisible) { applyImmersive(playerVisible) }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) openVideo(uri)
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
                    HomeScreen(onOpenVideo = { picker.launch(arrayOf("video/*")) })
                }
            }
        }
    }

    /** mpv 直接吃 content:// URI：ffmpeg 的 android_content 协议经应用 ContentResolver 读文件。 */
    private fun openVideo(uri: Uri) {
        pendingUrl = uri.toString()
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
}
