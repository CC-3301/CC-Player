package com.cc3301.ccplayer.library

/** 媒体条目的来源；网络源沿同一抽象接入。 */
enum class MediaSource { Local, Network }

/**
 * 媒体条目的统一抽象：本地 SAF 文档与网络源共用一种形状。
 *
 * [location] 统一用字符串表示位置：本地是 SAF URI，网络源是协议 URL。
 * 位置由各来源自己解释，上层只把它当作不透明标识。
 */
data class MediaFile(
    val name: String,
    val location: String,
    val size: Long = 0,
    val lastModified: Long = 0,
    val isDirectory: Boolean = false,
    val source: MediaSource = MediaSource.Local,
)
