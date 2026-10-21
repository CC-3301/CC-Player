package com.cc3301.ccplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.media.app.NotificationCompat.MediaStyle
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.CachedMediaPreviewSource
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.ThumbnailCache
import com.cc3301.ccplayer.library.ThumbnailPolicy
import com.cc3301.ccplayer.library.thumbnailCacheKey
import com.cc3301.ccplayer.progress.RoomPlaybackProgressRepository
import com.cc3301.ccplayer.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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
    private lateinit var thumbnailCache: ThumbnailCache

    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 通知刷新串行化：状态与每秒进度都可能触发，只保留最新一次。 */
    private var refreshJob: Job? = null
    private var cachedCoverKey: String? = null
    private var cachedCover: CoverArt? = null
    private var appIconBitmap: Bitmap? = null

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
            onPolicyChanged = ::requestRefresh,
        )
        playbackEngine.initialize()
        audioOutputMonitor = AndroidAudioOutputMonitor(this)
        audioOutputMonitor.start(playbackController::handleAudioOutputLost)
        thumbnailCache = CachedMediaPreviewSource.thumbnailCache(applicationContext)
        createNotificationChannel()
        setUpMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification(playbackController.state.value, null, appIconCover()))
        stateScope.launch {
            combine(
                playbackController.state,
                playbackController.switching,
                playbackController.restoring,
                playbackController.currentIndex,
                playbackController.playlist,
            ) { _, _, _, _, _ -> Unit }.collect { requestRefresh() }
        }
        stateScope.launch {
            playbackController.progress
                .map { it.durationMillis }
                .distinctUntilChanged()
                .collect { requestRefresh() }
        }
        stateScope.launch {
            while (isActive) {
                delay(NOTIFICATION_REFRESH_INTERVAL_MILLIS)
                if (notificationRefreshIntervalMillis(playbackController.state.value) != null) {
                    requestRefresh()
                }
            }
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

                    override fun onSeekTo(pos: Long) = seekTo(pos)
                },
            )
            isActive = true
            setPlaybackState(playbackState(playbackController.state.value, playbackController.progress.value))
        }
    }

    /** 按策略决定通知栏动作是否执行；首次加载、目标加载与恢复期间按可用性拦截。 */
    private fun dispatch(action: MediaSessionAction) {
        val controller = playbackController
        dispatchMediaAction(
            action,
            controller.state.value,
            controller.switching.value,
            controller.restoring.value,
        ) { allowedAction ->
            backgroundPolicy.dispatch(allowedAction) { passed -> passed.applyTo(controller) }
        }
    }

    /** 系统拖动定位：只在可拖动时落到播放位置。 */
    private fun seekTo(positionMillis: Long) {
        val controller = playbackController
        val state = controller.state.value
        val switching = controller.switching.value
        if (!mediaSeekAllowed(
                hasMedia = currentMedia() != null,
                switching = switching,
                initialLoading = isInitialLoading(state, switching),
                restoring = controller.restoring.value,
                blocksBackgroundPlayback = backgroundPolicy.blocksBackgroundPlayback,
            )
        ) {
            return
        }
        controller.updateSeekPreview(positionMillis)
        controller.commitSeekPreview()
        requestRefresh()
    }

    /** 状态、加载阶段、前后台或播放位置变化时同步 MediaSession 与通知栏。 */
    private fun requestRefresh() {
        refreshJob?.cancel()
        refreshJob = stateScope.launch { refreshPlaybackControls() }
    }

    private suspend fun refreshPlaybackControls() {
        val controller = playbackController
        val state = controller.state.value
        val progress = controller.progress.value
        val media = currentMedia()
        val cover = coverFor(media)
        mediaSession.setMetadata(mediaMetadata(media, progress, cover))
        mediaSession.setPlaybackState(playbackState(state, progress))
        startForeground(NOTIFICATION_ID, buildNotification(state, media, cover))
    }

    private fun currentMedia(): MediaFile? {
        val controller = playbackController
        return controller.playlist.value.getOrNull(controller.currentIndex.value)
    }

    /** 缩略图缓存按缓存键取一次；同一媒体刷新时复用已解码的封面。 */
    private suspend fun coverFor(media: MediaFile?): CoverArt {
        if (media == null) return appIconCover()
        val key = thumbnailCacheKey(media, ThumbnailPolicy())
        cachedCover?.takeIf { cachedCoverKey == key }?.let { return it }
        val image = thumbnailCache.file(key)
        val bitmap = image?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val cover = if (image != null && bitmap != null) {
            CoverArt(bitmap, FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, image))
        } else {
            appIconCover()
        }
        cachedCoverKey = key
        cachedCover = cover
        return cover
    }

    private fun appIconCover(): CoverArt {
        val bitmap = appIconBitmap ?: run {
            val drawable = packageManager.getApplicationIcon(packageName)
            val size = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight).takeIf { it > 0 } ?: APP_ICON_SIZE
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { icon ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(icon))
            }
        }.also { appIconBitmap = it }
        return CoverArt(bitmap, null)
    }

    private fun mediaMetadata(
        media: MediaFile?,
        progress: PlaybackProgress,
        cover: CoverArt,
    ): MediaMetadataCompat {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mediaTitle(media))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, notificationDurationMillis(progress))
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover.bitmap)
        cover.uri?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it.toString()) }
        return builder.build()
    }

    private fun mediaTitle(media: MediaFile?): String =
        media?.let { notificationTitle(it.name) } ?: getString(R.string.app_name)

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(state: PlaybackState, media: MediaFile?, cover: CoverArt): Notification {
        val actions = currentActions(state)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(mediaTitle(media))
            .setContentText(getString(R.string.notification_playing))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(cover.bitmap)
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

    /** 当前加载阶段与后台策略下可用的媒体动作。 */
    private fun currentActions(state: PlaybackState): List<MediaSessionAction> {
        val switching = playbackController.switching.value
        return backgroundPolicy.notificationActions(
            state = state,
            switching = switching,
            initialLoading = isInitialLoading(state, switching),
            restoring = playbackController.restoring.value,
        )
    }

    private fun playbackState(state: PlaybackState, progress: PlaybackProgress): PlaybackStateCompat {
        val switching = playbackController.switching.value
        val initialLoading = isInitialLoading(state, switching)
        val restoring = playbackController.restoring.value
        val seekable = mediaSeekAllowed(
            hasMedia = currentMedia() != null,
            switching = switching,
            initialLoading = initialLoading,
            restoring = restoring,
            blocksBackgroundPlayback = backgroundPolicy.blocksBackgroundPlayback,
        )
        return PlaybackStateCompat.Builder()
            .setActions(playbackStateActions(currentActions(state), seekable))
            .setState(
                when (state) {
                    PlaybackState.Idle -> PlaybackStateCompat.STATE_NONE
                    PlaybackState.Loading -> PlaybackStateCompat.STATE_BUFFERING
                    PlaybackState.Playing -> PlaybackStateCompat.STATE_PLAYING
                    PlaybackState.Paused -> PlaybackStateCompat.STATE_PAUSED
                    PlaybackState.Ended -> PlaybackStateCompat.STATE_STOPPED
                },
                progress.positionMillis,
                if (state == PlaybackState.Playing) 1f else 0f,
            )
            .build()
    }

    private data class CoverArt(val bitmap: Bitmap, val uri: Uri?)

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
        const val MEDIA_SESSION_TAG = "ccplayer-playback"
        const val FILE_PROVIDER_AUTHORITY = "com.cc3301.ccplayer.fileprovider"
        const val APP_ICON_SIZE = 192
    }
}
