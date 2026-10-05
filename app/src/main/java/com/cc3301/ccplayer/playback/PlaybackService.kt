package com.cc3301.ccplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.progress.RoomPlaybackProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 前台播放服务：唯一持有 mpv 实例与播放控制层，界面只绑定取用。
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var playbackEngine: MpvPlaybackEngine
    private lateinit var playbackController: PlaybackController

    val engine: PlaybackEngine get() = playbackEngine

    val controller: PlaybackController get() = playbackController

    override fun onCreate() {
        super.onCreate()
        playbackEngine = MpvPlaybackEngine(applicationContext, cacheDir.absolutePath)
        playbackController = PlaybackController(playbackEngine, RoomPlaybackProgressRepository.create(this), scope)
        playbackEngine.initialize()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onDestroy() {
        scope.cancel()
        playbackEngine.release()
        super.onDestroy()
    }

    /** 同进程绑定，界面直接用这个拿引擎与播放控制层。 */
    inner class LocalBinder : Binder() {
        val engine: PlaybackEngine get() = this@PlaybackService.engine

        val controller: PlaybackController get() = this@PlaybackService.controller
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_playing))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
    }
}
