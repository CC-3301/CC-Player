package com.cc3301.ccplayer.progress

/** 一个媒体的播放进度记录，媒体位置同时是记录的键。 */
data class MediaProgress(
    val location: String,
    /** 上次播放到的位置，单位毫秒。 */
    val positionMillis: Long,
    /** 落记录时的媒体总时长，单位毫秒。 */
    val durationMillis: Long,
    /** 落记录时媒体的修改时间。 */
    val lastModified: Long,
)
