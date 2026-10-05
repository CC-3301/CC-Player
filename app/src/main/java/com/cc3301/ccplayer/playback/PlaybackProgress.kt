package com.cc3301.ccplayer.playback

/** 当前媒体的播放位置与总时长，单位毫秒。 */
data class PlaybackProgress(
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
)
