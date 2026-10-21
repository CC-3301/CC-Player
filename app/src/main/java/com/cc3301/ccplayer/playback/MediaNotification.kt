package com.cc3301.ccplayer.playback

import java.io.File

/** 通知封面：优先用缩略图缓存文件，缺失时退回应用图标。 */
sealed interface NotificationCover {
    data class CachedImage(val file: File) : NotificationCover

    data object AppIcon : NotificationCover
}

/** 通知标题：文件名去掉最后一个扩展名；隐藏文件与无扩展名文件名原样保留。 */
fun notificationTitle(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) fileName.substring(0, dot) else fileName
}

/** 通知显示的总时长与播放页一致，取自同一份播放进度。 */
fun notificationDurationMillis(progress: PlaybackProgress): Long = progress.durationMillis

fun notificationCover(cachedFile: File?): NotificationCover =
    cachedFile?.let(NotificationCover::CachedImage) ?: NotificationCover.AppIcon

/** 播放中约每秒刷新一次通知位置；其余状态不刷新。 */
fun notificationRefreshIntervalMillis(state: PlaybackState): Long? =
    if (state == PlaybackState.Playing) NOTIFICATION_REFRESH_INTERVAL_MILLIS else null

const val NOTIFICATION_REFRESH_INTERVAL_MILLIS = 1_000L
