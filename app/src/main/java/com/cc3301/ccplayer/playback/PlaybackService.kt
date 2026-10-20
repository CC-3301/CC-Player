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
import com.cc3301.ccplayer.progress.RoomPlaybackProgressRepository
import com.cc3301.ccplayer.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台播放服务：唯一持有 mpv 实例、播放控制层与 MediaSession，界面只绑定取用。
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var playbackEngine: MpvPlaybackEngine
    private lateinit var playbackController: PlaybackController
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioOutputMonitor: AudioOutputMonitor
    private lateinit var backgroundPolicy: BackgroundPlaybackPolicy

    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val engine: PlaybackEngine get() = playbackEngine

    val controller: PlaybackController get() = playbackController

    override fun onCreate() {
        super.onCreate()
        playbackEngine = MpvPlaybackEngine(applicationContext, cacheDir.absolutePath)
        playbackController = PlaybackController(
            playbackEngine,
            RoomPlaybackProgressRepository.create(this),
            scope,
            marshalCallbacks = true,
        )
        backgroundPolicy = BackgroundPlaybackPolicy(
            pausePlayback = playbackController::pause,
            onPolicyChanged = ::refreshPlaybackControls,
        )
        playbackEngine.initialize()
        audioOutputMonitor = AndroidAudioOutputMonitor(this)
        audioOutputMonitor.start(playbackController::handleAudioOutputLost)
        createNotificationChannel()
        setUpMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification(playbackController.state.value))
        stateScope.launch {
            playbackController.state.collect(::refreshPlaybackControls)
        }
        scope.launch {
            SettingsRepository.create(applicationContext).backgroundPlayback.collect { enabled ->
                backgroundPolicy.setBackgroundPlaybackEnabled(enabled)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaSessionAction.fromIntentAction(intent?.action)?.let(::dispatch)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        stateScope.cancel()
        scope.cancel()
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

        /** 界面只上报前后台；暂停与通知动作由服务内的策略统一决定。 */
        fun setAppInForeground(foreground: Boolean) {
            this@PlaybackService.backgroundPolicy.setAppInForeground(foreground)
        }
    }

    /** 通知栏与系统媒体控制都经 MediaSession 回调到同一个播放控制层。 */
    private fun setUpMediaSession() {
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() = dispatch(MediaSessionAction.Play)

                    override fun onPause() = dispatch(MediaSessionAction.Pause)

                    override fun onSkipToNext() = dispatch(MediaSessionAction.Next)

                    override fun onSkipToPrevious() = dispatch(MediaSessionAction.Previous)
                },
            )
            isActive = true
            setPlaybackState(playbackState(playbackController.state.value))
        }
    }

    /** 按策略决定通知栏动作是否执行；后台关闭时只放过暂停。 */
    private fun dispatch(action: MediaSessionAction) {
        backgroundPolicy.dispatch(action) { allowed -> allowed.applyTo(playbackController) }
    }

    /** 状态或前后台变化时同步 MediaSession 与通知栏，前后台切换后按钮集合随之变化。 */
    private fun refreshPlaybackControls(state: PlaybackState = playbackController.state.value) {
        mediaSession.setPlaybackState(playbackState(state))
        startForeground(NOTIFICATION_ID, buildNotification(state))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(state: PlaybackState): Notification {
        val actions = backgroundPolicy.notificationActions(state)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_playing))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
        actions.forEach { action ->
            builder.addAction(actionIcon(action), actionLabel(action), actionIntent(action))
        }
        return builder
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(*actions.indices.toList().toIntArray()),
            )
            .build()
    }

    private fun actionIcon(action: MediaSessionAction): Int = when (action) {
        MediaSessionAction.Play -> android.R.drawable.ic_media_play
        MediaSessionAction.Pause -> android.R.drawable.ic_media_pause
        MediaSessionAction.Previous -> android.R.drawable.ic_media_previous
        MediaSessionAction.Next -> android.R.drawable.ic_media_next
    }

    private fun actionLabel(action: MediaSessionAction): String = getString(
        when (action) {
            MediaSessionAction.Play -> R.string.player_resume
            MediaSessionAction.Pause -> R.string.player_pause
            MediaSessionAction.Previous -> R.string.player_previous
            MediaSessionAction.Next -> R.string.player_next
        },
    )

    /** 通知栏按钮回自己的服务，由 [onStartCommand] 转成控制层调用。 */
    private fun actionIntent(action: MediaSessionAction): PendingIntent = PendingIntent.getService(
        this,
        action.ordinal,
        Intent(this, PlaybackService::class.java).setAction(action.intentAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun playbackState(state: PlaybackState): PlaybackStateCompat {
        val actions = if (backgroundPolicy.blocksBackgroundPlayback) {
            PlaybackStateCompat.ACTION_PAUSE
        } else {
            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        return PlaybackStateCompat.Builder()
            .setActions(actions)
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
    }

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
        const val MEDIA_SESSION_TAG = "ccplayer-playback"
    }
}
