package com.cc3301.ccplayer.playback

/**
 * 生效中的字幕：内嵌轨道、外挂字幕文件、关闭。任一时刻只有一路。
 */
sealed interface SubtitleSelection {

    /** 内嵌字幕轨道生效。 */
    data class Embedded(val trackId: Int) : SubtitleSelection

    /** 外挂字幕文件生效，[location] 是同目录里匹配到的字幕位置。 */
    data class External(val location: String) : SubtitleSelection

    /** 关闭字幕显示。 */
    data object Off : SubtitleSelection
}
