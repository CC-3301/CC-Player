package com.cc3301.ccplayer.library

/** 可播放媒体的种类。 */
enum class MediaKind { Video, Audio }

private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "wmv")
private val audioExtensions = setOf("mp3", "opus", "wav", "flac", "m4a", "aac", "ogg")

/** 按扩展名判定媒体种类；不是媒体文件时返回 null。 */
fun mediaKindOf(fileName: String): MediaKind? = when (val extension = fileName.substringAfterLast('.', "").lowercase()) {
    in videoExtensions -> MediaKind.Video
    in audioExtensions -> MediaKind.Audio
    else -> null
}
