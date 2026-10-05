package com.cc3301.ccplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.cc3301.ccplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台播放服务：唯一持有 mpv 实例、播放控制层与 MediaSession，界面只绑定取用。
 */
class PlaybackService : Service() {

    private lateinit var playbackEngine: MpvPlaybackEngine
    private lateinit var playbackController: PlaybackController
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioOutputMonitor: AudioOutputMonitor

    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val engine: PlaybackEngine get() = playbackEngine

    val controller: PlaybackController get() = playbackController

    override fun onCreate() {
        super.onCreate()
        playbackEngine = MpvPlaybackEngine(applicationContext, cacheDir.absolutePath)
        playbackController = PlaybackController(playbackEngine)
        playbackEngine.initialize()
        audioOutputMonitor = AndroidAudioOutputMonitor(this)
        audioOutputMonitor.start(playbackController::handleAudioOutputLost)
        createNotificationChannel()
        setUpMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification(playbackController.state.value))
        stateScope.launch {
            playbackController.state.collect { state ->
                mediaSession.setPlaybackState(playbackState(state))
                startForeground(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaSessionAction.fromIntentAction(intent?.action)?.applyTo(playbackController)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        stateScope.cancel()
        audioOutputMonitor.stop()
        mediaSession.isActive = false
        mediaSession.release()
        playbackEngine.release()
        super.onDestroy()
    }

    /** 同进程绑定，界面直接用这个拿引擎与播放控制层。 */
    inner class LocalBinder : Binder() {
        val engine: PlaybackEngine get() = this@PlaybackService.engine

        val controller: PlaybackController get() = this@PlaybackService.controller
    }

    /** 通知栏与系统媒体控制都经 MediaSession 回调到同一个播放控制层。 */
    private fun setUpMediaSession() {
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() = MediaSessionAction.Play.applyTo(playbackController)

                    override fun onPause() = MediaSessionAction.Pause.applyTo(playbackController)

                    override fun onSkipToNext() = MediaSessionAction.Next.applyTo(playbackController)

                    override fun onSkipToPrevious() = MediaSessionAction.Previous.applyTo(playbackController)
                },
            )
            isActive = true
            setPlaybackState(playbackState(playbackController.state.value))
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(state: PlaybackState): Notification {
        val toggle = mediaSessionActionFor(state)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_playing))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.player_previous),
                actionIntent(MediaSessionAction.Previous),
            )
            .addAction(toggleIcon(toggle), toggleLabel(toggle), actionIntent(toggle))
            .addAction(android.R.drawable.ic_media_next, getString(R.string.player_next), actionIntent(MediaSessionAction.Next))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(PREVIOUS_SLOT, TOGGLE_SLOT, NEXT_SLOT),
            )
            .build()
    }

    private fun toggleIcon(action: MediaSessionAction): Int =
        if (action == MediaSessionAction.Pause) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

    private fun toggleLabel(action: MediaSessionAction): String =
        getString(if (action == MediaSessionAction.Pause) R.string.player_pause else R.string.player_resume)

    /** 通知栏按钮回自己的服务，由 [onStartCommand] 转成控制层调用。 */
    private fun actionIntent(action: MediaSessionAction): PendingIntent = PendingIntent.getService(
        this,
        action.ordinal,
        Intent(this, PlaybackService::class.java).setAction(action.intentAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun playbackState(state: PlaybackState): PlaybackStateCompat = PlaybackStateCompat.Builder()
        .setActions(
            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
        )
        .setState(
            when (state) {
                PlaybackState.Idle -> PlaybackStateCompat.STATE_NONE
                PlaybackState.Loading -> PlaybackStateCompat.STATE_BUFFERING
                PlaybackState.Playing -> PlaybackStateCompat.STATE_PLAYING
                PlaybackState.Paused -> PlaybackStateCompat.STATE_PAUSED
                PlaybackState.Ended -> PlaybackStateCompat.STATE_STOPPED
            },
            playbackController.progress.value.positionMillis,
            1f,
        )
        .build()

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
        const val MEDIA_SESSION_TAG = "ccplayer-playback"
        const val PREVIOUS_SLOT = 0
        const val TOGGLE_SLOT = 1
        const val NEXT_SLOT = 2
    }
}
