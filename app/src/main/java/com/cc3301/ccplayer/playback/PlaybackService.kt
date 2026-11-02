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
import androidx.core.graphics.createBitmap
import androidx.media.app.NotificationCompat.MediaStyle
import com.cc3301.ccplayer.CcPlayerApplication
import com.cc3301.ccplayer.R
import com.cc3301.ccplayer.library.CachedMediaPreviewSource
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.progress.RoomPlaybackProgressRepository
import com.cc3301.ccplayer.settings.SettingsRepository
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var coverCache: NotificationCoverCache

    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 通知刷新串行化：状态与每秒进度都可能触发，只保留最新一次。 */
    private var refreshJob: Job? = null
    private val progressTrigger = NotificationProgressTrigger()
    private var appIconBitmap: Bitmap? = null
    private var coverBitmapPath: String? = null
    private var coverBitmap: Bitmap? = null
    private var grantedCoverUri: Uri? = null

    private val fileProviderAuthority: String get() = "$packageName.fileprovider"

    val engine: PlaybackEngine get() = playbackEngine

    val controller: PlaybackController get() = playbackController

    override fun onCreate() {
        super.onCreate()
        val diagnostics = (application as CcPlayerApplication).diagnostics
        playbackEngine = MpvPlaybackEngine(applicationContext, cacheDir.absolutePath, diagnostics)
        playbackController = PlaybackController(
            playbackEngine,
            RoomPlaybackProgressRepository.create(this),
            scope,
            marshalCallbacks = true,
            diagnostics = diagnostics,
            probeTarget = ::probeTargetReadable,
        )
        backgroundPolicy = BackgroundPlaybackPolicy(
            pausePlayback = playbackController::pauseForBackground,
            onPolicyChanged = ::requestRefresh,
        )
        playbackEngine.initialize()
        audioOutputMonitor = AndroidAudioOutputMonitor(this)
        audioOutputMonitor.start(playbackController::handleAudioOutputLost)
        coverCache = NotificationCoverCache(CachedMediaPreviewSource.thumbnailCache(applicationContext))
        createNotificationChannel()
        setUpMediaSession()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(notificationContent(currentMedia(), coverFile = null), appIconBitmap()),
        )
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
            playbackController.progress.collect { current ->
                if (progressTrigger.shouldRefresh(playbackController.state.value, current)) {
                    requestRefresh()
                }
            }
        }
        stateScope.launch {
            var coverRetryElapsed = 0L
            while (isActive) {
                delay(NOTIFICATION_REFRESH_INTERVAL_MILLIS)
                if (notificationRefreshIntervalMillis(playbackController.state.value) != null) {
                    coverRetryElapsed = 0L
                    requestRefresh()
                } else if (currentMedia() != null) {
                    coverRetryElapsed += NOTIFICATION_REFRESH_INTERVAL_MILLIS
                    if (coverRetryElapsed >= COVER_RETRY_INTERVAL_MILLIS) {
                        coverRetryElapsed = 0L
                        requestRefresh()
                    }
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

    /**
     * 切集预检：打开目标读取句柄读 1 个字节后立即关闭。
     * 本地走 ContentResolver，网络走与封面相同的随机读接缝；都不占解码许可。
     */
    private suspend fun probeTargetReadable(media: MediaFile): Boolean = withContext(Dispatchers.IO) {
        if (media.source == MediaSource.Network) {
            val reads = (application as CcPlayerApplication).networkReads
            probeFirstByte(reads.openRead(media.location, media.size)) { source ->
                source.readAt(0, ByteArray(1), 0, 1) > 0
            }
        } else {
            val stream = contentResolver.openInputStream(Uri.parse(media.location))
                ?: return@withContext false
            probeFirstByte(stream) { it.read(ByteArray(1)) >= 0 }
        }
    }

    /**
     * 读 1 个字节后关闭。阻塞读不响应协程取消，到 [PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS]
     * 主动关闭句柄让它退出，预检超时时点击仍有反馈。
     */
    private suspend fun <T : Closeable> probeFirstByte(target: T, read: (T) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            // 阻塞读不响应协程取消：计时器不随预检协程一起被取消，到点主动关闭句柄把读打断。
            val closeOnTimeout = scope.launch(Dispatchers.IO) {
                delay(PlaybackController.SWITCH_PRECHECK_TIMEOUT_MILLIS)
                runCatching { target.close() }
            }
            try {
                read(target)
            } finally {
                closeOnTimeout.cancel()
                runCatching { target.close() }
            }
        }

    override fun onDestroy() {
        stateScope.cancel()
        scope.cancel()
        audioOutputMonitor.stop()
        grantedCoverUri?.let {
            runCatching { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        mediaSession.isActive = false
        mediaSession.release()
        playbackEngine.release()
        super.onDestroy()
    }

    /** 同进程绑定，界面直接用这个拿引擎与播放控制层。 */
    inner class LocalBinder : Binder() {
        val engine: PlaybackEngine get() = this@PlaybackService.engine

        val controller: PlaybackController get() = this@PlaybackService.controller

        /** 后台播放关闭且已退到后台：此时播放已按策略暂停。 */
        val blocksBackgroundPlayback: Boolean
            get() = this@PlaybackService.backgroundPolicy.blocksBackgroundPlayback

        /** 界面只上报前后台；暂停与通知动作由服务内的策略统一决定。 */
        fun setAppInForeground(foreground: Boolean) {
            // 切集计时按真实前后台挂起，与 Surface 是否还在无关。
            this@PlaybackService.playbackController.setAppInForeground(foreground)
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
            setPlaybackState(
                playbackState(
                    playbackController.state.value,
                    notificationContent(currentMedia(), coverFile = null),
                ),
            )
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
            blocksBackgroundPlayback = backgroundPolicy.blocksBackgroundPlayback,
        ) { allowedAction ->
            backgroundPolicy.dispatch(allowedAction) { passed -> passed.applyTo(controller) }
        }
    }

    /** 系统拖动定位：只在可拖动时落到播放位置，位置回调到达后由通知刷新跟随。 */
    private fun seekTo(positionMillis: Long) {
        val controller = playbackController
        val state = controller.state.value
        val switching = controller.switching.value
        if (!mediaSeekAllowed(
                state = state,
                hasMedia = currentMedia() != null,
                switching = switching,
                initialLoading = isInitialLoading(state, switching),
                restoring = controller.restoring.value,
                blocksBackgroundPlayback = backgroundPolicy.blocksBackgroundPlayback,
            )
        ) {
            return
        }
        controller.seekTo(positionMillis)
    }

    /** 状态、加载阶段、前后台或播放位置变化时同步 MediaSession 与通知栏。 */
    private fun requestRefresh() {
        refreshJob?.cancel()
        refreshJob = stateScope.launch { refreshPlaybackControls() }
    }

    private suspend fun refreshPlaybackControls() {
        val state = playbackController.state.value
        val media = currentMedia()
        val content = notificationContent(media, coverCache.file(media))
        val coverBitmap = coverBitmap(content.coverFile)
        mediaSession.setMetadata(mediaMetadata(content, coverUri(content.coverFile)))
        mediaSession.setPlaybackState(playbackState(state, content))
        startForeground(NOTIFICATION_ID, buildNotification(content, coverBitmap))
    }

    private fun currentMedia(): MediaFile? {
        val controller = playbackController
        return controller.playlist.value.getOrNull(controller.currentIndex.value)
    }

    private fun notificationContent(media: MediaFile?, coverFile: File?): MediaNotificationContent {
        val state = playbackController.state.value
        val switching = playbackController.switching.value
        return mediaNotificationContent(
            state = state,
            media = media,
            progress = playbackController.progress.value,
            switching = switching,
            initialLoading = isInitialLoading(state, switching),
            restoring = playbackController.restoring.value,
            blocksBackgroundPlayback = backgroundPolicy.blocksBackgroundPlayback,
            coverFile = coverFile,
            fallbackTitle = getString(R.string.app_name),
        )
    }

    /** 封面解码放到 IO；按 256px 上限降采样并按文件路径复用，换媒体时丢弃旧位图。 */
    private suspend fun coverBitmap(file: File?): Bitmap {
        if (file == null) {
            coverBitmapPath = null
            coverBitmap = null
            return appIconBitmap()
        }
        val path = file.absolutePath
        if (coverBitmapPath == path) coverBitmap?.let { return it }
        val decoded = withContext(Dispatchers.IO) { decodeCover(file) }
        if (decoded == null) {
            coverBitmapPath = null
            coverBitmap = null
            return appIconBitmap()
        }
        coverBitmapPath = path
        coverBitmap = decoded
        return decoded
    }

    private fun decodeCover(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest > 0 && longest / sample > MAX_COVER_SIZE) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun coverUri(file: File?): Uri? {
        val uri = file?.let {
            runCatching { FileProvider.getUriForFile(this, fileProviderAuthority, it) }.getOrNull()
        }
        val previous = grantedCoverUri
        if (previous != null && previous != uri) {
            runCatching { revokeUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            grantedCoverUri = null
        }
        if (uri == null) return null
        if (grantedCoverUri != uri) {
            runCatching {
                grantUriPermission(SYSTEM_UI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onSuccess { grantedCoverUri = uri }
        }
        return uri
    }

    /**
     * 封面取不到时的回退图标：直接把应用图标的前景层画在图标底色上。
     * 不能用 [android.content.pm.PackageManager.getApplicationIcon]：它返回自适应图标，
     * 按画布直接绘制会叠出多层三角。
     */
    private fun appIconBitmap(): Bitmap {
        appIconBitmap?.let { return it }
        val bitmap = createBitmap(APP_ICON_SIZE, APP_ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(getColor(R.color.ic_launcher_background))
        getDrawable(R.drawable.ic_launcher_foreground)?.let { foreground ->
            foreground.setBounds(0, 0, APP_ICON_SIZE, APP_ICON_SIZE)
            foreground.draw(canvas)
        }
        appIconBitmap = bitmap
        return bitmap
    }

    private fun mediaMetadata(content: MediaNotificationContent, coverUri: Uri?): MediaMetadataCompat {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, content.title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, content.durationMillis)
        coverUri?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it.toString()) }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(content: MediaNotificationContent, cover: Bitmap): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(getString(R.string.notification_playing))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(cover)
            .setOngoing(true)
            .setSilent(true)
        content.actions.forEach { action ->
            builder.addAction(actionIcon(action), actionLabel(action), actionIntent(action))
        }
        return builder
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(*content.actions.indices.toList().toIntArray()),
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

    private fun playbackState(state: PlaybackState, content: MediaNotificationContent): PlaybackStateCompat {
        val switching = playbackController.switching.value
        val seekable = mediaSeekAllowed(
            state = state,
            hasMedia = currentMedia() != null,
            switching = switching,
            initialLoading = isInitialLoading(state, switching),
            restoring = playbackController.restoring.value,
            blocksBackgroundPlayback = backgroundPolicy.blocksBackgroundPlayback,
        )
        return PlaybackStateCompat.Builder()
            .setActions(playbackStateActions(content.actions, seekable))
            .setState(
                when (state) {
                    PlaybackState.Idle -> PlaybackStateCompat.STATE_NONE
                    PlaybackState.Loading -> PlaybackStateCompat.STATE_BUFFERING
                    PlaybackState.Playing -> PlaybackStateCompat.STATE_PLAYING
                    PlaybackState.Paused -> PlaybackStateCompat.STATE_PAUSED
                    PlaybackState.Ended -> PlaybackStateCompat.STATE_STOPPED
                },
                content.positionMillis,
                if (state == PlaybackState.Playing) 1f else 0f,
            )
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
        const val MEDIA_SESSION_TAG = "ccplayer-playback"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val MAX_COVER_SIZE = 256
        const val APP_ICON_SIZE = 192
    }
}
