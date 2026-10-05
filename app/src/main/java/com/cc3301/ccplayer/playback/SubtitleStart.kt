package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.matchExternalSubtitles

/**
 * 起播时的字幕起点。
 */
data class SubtitleStart(
    /** 加载进轨道列表、但不起播就显示的外挂字幕，按得分从高到低。 */
    val externalToLoad: List<MediaFile>,
    /** 起播后显示的那一路。 */
    val selection: SubtitleSelection,
)

/**
 * 起播字幕决策：同目录匹配到的外挂字幕一律加载进轨道列表，显示哪一路按内嵌优先定。
 *
 * 有内嵌字幕轨道时显示带默认标记的那条，没有默认标记时显示第一条；没有内嵌时显示得分最高的外挂；
 * 都没有则不显示字幕。
 */
fun subtitleStartDecision(
    mediaName: String,
    tracks: List<SubtitleTrack>,
    directoryEntries: List<MediaFile>,
): SubtitleStart {
    val external = matchExternalSubtitles(mediaName, directoryEntries)
    val embedded = tracks.firstOrNull { it.isDefault } ?: tracks.firstOrNull()
    val selection = embedded
        ?.let { SubtitleSelection.Embedded(it.id) }
        ?: external.firstOrNull()?.let { SubtitleSelection.External(it.location) }
        ?: SubtitleSelection.Off
    return SubtitleStart(externalToLoad = external, selection = selection)
}
