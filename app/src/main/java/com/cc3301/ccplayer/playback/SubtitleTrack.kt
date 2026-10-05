package com.cc3301.ccplayer.playback

/** 当前媒体的内嵌字幕轨道。[id] 是播放核心的轨道号。 */
data class SubtitleTrack(val id: Int, val language: String?, val title: String?)
