package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.ThumbnailCache
import com.cc3301.ccplayer.library.ThumbnailPolicy
import com.cc3301.ccplayer.library.previewTargetWidth
import com.cc3301.ccplayer.library.thumbnailCacheKey
import java.io.File

/** 点击媒体通知回到播放页的入口动作。 */
const val OPEN_PLAYER_ACTION = "com.cc3301.ccplayer.action.OPEN_PLAYER"

/** 通知展示内容：标题、时长、封面文件与动作集合。 */
data class MediaNotificationContent(
    val title: String,
    val durationMillis: Long,
    val positionMillis: Long,
    val coverFile: File?,
    val actions: List<MediaSessionAction>,
)

/**
 * 按播放状态、当前媒体与加载阶段生成通知内容。
 * 封面只给出缓存文件；取不到时由界面层退回应用图标。
 */
fun mediaNotificationContent(
    state: PlaybackState,
    media: MediaFile?,
    progress: PlaybackProgress,
    initialLoading: Boolean,
    blocksBackgroundPlayback: Boolean,
    coverFile: File?,
    fallbackTitle: String,
): MediaNotificationContent = MediaNotificationContent(
    title = media?.let { notificationTitle(it.name) } ?: fallbackTitle,
    durationMillis = notificationDurationMillis(progress),
    positionMillis = progress.positionMillis,
    coverFile = coverFile,
    actions = mediaNotificationActions(
        state = state,
        initialLoading = initialLoading,
        blocksBackgroundPlayback = blocksBackgroundPlayback,
    ),
)

/** 通知标题：文件名去掉最后一个扩展名；隐藏文件与无扩展名文件名原样保留。 */
fun notificationTitle(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(0, dot) else fileName
}

/** 通知显示的总时长与播放页一致，取自同一份播放进度。 */
fun notificationDurationMillis(progress: PlaybackProgress): Long = progress.durationMillis

/** 播放中约每秒刷新一次通知位置；其余状态不刷新。 */
fun notificationRefreshIntervalMillis(state: PlaybackState): Long? =
    if (state == PlaybackState.Playing) NOTIFICATION_REFRESH_INTERVAL_MILLIS else null

/**
 * 位置回调是否要立即刷新：播放中由每秒定时器负责，暂停时必须跟随位置变化，
 * 这样暂停态拖动定位后通知不会停在旧值。
 */
fun shouldRefreshNotification(
    state: PlaybackState,
    previous: PlaybackProgress,
    current: PlaybackProgress,
): Boolean = state != PlaybackState.Playing || previous.durationMillis != current.durationMillis

/** 服务内的位置回调触发器：保留上一次进度，决定本次回调是否要刷新通知。 */
internal class NotificationProgressTrigger {
    private var previous = PlaybackProgress()

    fun shouldRefresh(state: PlaybackState, current: PlaybackProgress): Boolean {
        val refresh = shouldRefreshNotification(state, previous, current)
        previous = current
        return refresh
    }
}

/**
 * 缩略图封面读取：每次都按缓存键向缓存查询，命中不跨刷新复用，
 * 文件被淘汰时能及时退回应用图标。
 */
internal class NotificationCoverCache(
    private val cache: ThumbnailCache,
    private val policy: ThumbnailPolicy = ThumbnailPolicy(),
) {
    suspend fun file(media: MediaFile?): File? {
        if (media == null) return null
        return cache.file(thumbnailCacheKey(media, policy, previewTargetWidth(media, policy)))
    }
}

const val NOTIFICATION_REFRESH_INTERVAL_MILLIS = 1_000L

/** 暂停或结束后为补上稍后落盘的缩略图，最多每 2 秒刷新一次。 */
const val COVER_RETRY_INTERVAL_MILLIS = 2_000L
