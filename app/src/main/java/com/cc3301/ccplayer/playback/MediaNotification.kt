package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.ThumbnailCache
import com.cc3301.ccplayer.library.ThumbnailPolicy
import com.cc3301.ccplayer.library.thumbnailCacheKey
import com.cc3301.ccplayer.library.thumbnailCacheMaxAge
import java.io.File

/** 通知展示内容：标题、时长、封面文件、动作集合与进度刷新节奏。 */
data class MediaNotificationContent(
    val title: String,
    val durationMillis: Long,
    val positionMillis: Long,
    val coverFile: File?,
    val actions: List<MediaSessionAction>,
    val refreshIntervalMillis: Long?,
)

/**
 * 按播放状态、当前媒体与加载阶段生成通知内容。
 * 封面只给出缓存文件；取不到时由界面层退回应用图标。
 */
fun mediaNotificationContent(
    state: PlaybackState,
    media: MediaFile?,
    progress: PlaybackProgress,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
    blocksBackgroundPlayback: Boolean,
    coverFile: File?,
    fallbackTitle: String,
): MediaNotificationContent = MediaNotificationContent(
    title = media?.let { notificationTitle(it.name) } ?: fallbackTitle,
    durationMillis = notificationDurationMillis(progress),
    positionMillis = progress.positionMillis,
    coverFile = coverFile,
    actions = notificationActions(
        state = state,
        switching = switching,
        initialLoading = initialLoading,
        restoring = restoring,
        blocksBackgroundPlayback = blocksBackgroundPlayback,
    ),
    refreshIntervalMillis = notificationRefreshIntervalMillis(state),
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

/**
 * 缩略图封面读取：命中后按缓存键复用；未命中不记负缓存，缩略图稍后落盘时下一次可补上。
 */
internal class NotificationCoverCache(
    private val cache: ThumbnailCache,
    private val policy: ThumbnailPolicy = ThumbnailPolicy(),
) {
    private var cachedKey: String? = null
    private var cachedFile: File? = null

    suspend fun file(media: MediaFile?): File? {
        if (media == null) return null
        val key = thumbnailCacheKey(media, policy)
        if (cachedFile != null && cachedKey == key) return cachedFile
        val file = cache.file(key, thumbnailCacheMaxAge(media, policy))
        if (file != null) {
            cachedKey = key
            cachedFile = file
        } else {
            cachedKey = null
            cachedFile = null
        }
        return file
    }
}

const val NOTIFICATION_REFRESH_INTERVAL_MILLIS = 1_000L
