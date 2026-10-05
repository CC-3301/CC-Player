package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaSource

/**
 * 条目交给播放核心的形式：网络媒体的位置先换成回环地址，身份 [MediaFile.location] 不变。
 * 目录与非网络条目原样返回。
 */
fun MediaFile.withPlaybackLocation(streams: NetworkStreams): MediaFile =
    if (isDirectory || source != MediaSource.Network) this else copy(playbackLocation = streams.urlFor(location))
