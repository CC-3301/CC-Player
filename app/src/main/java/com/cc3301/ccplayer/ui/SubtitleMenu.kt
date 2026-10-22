package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.playback.SubtitleSelection
import com.cc3301.ccplayer.playback.SubtitleTrack

/** 字幕菜单里的一项。 */
sealed interface SubtitleMenuItem {

    /** 条目的显示名。 */
    val label: String

    /** 当前生效。 */
    val active: Boolean

    /** 内嵌字幕轨道。 */
    data class Embedded(
        val trackId: Int,
        override val label: String,
        override val active: Boolean,
    ) : SubtitleMenuItem

    /** 外挂字幕文件。 */
    data class External(
        val location: String,
        override val label: String,
        override val active: Boolean,
    ) : SubtitleMenuItem

    /** 关闭字幕；显示名用界面的文案。 */
    data class Off(override val active: Boolean) : SubtitleMenuItem {
        override val label: String get() = ""
    }
}

/** 菜单构成：内嵌轨道、可匹配外挂、关闭；[selection] 决定哪一项标为生效。 */
fun subtitleMenuItems(
    tracks: List<SubtitleTrack>,
    external: List<MediaFile>,
    selection: SubtitleSelection,
): List<SubtitleMenuItem> = buildList {
    tracks.forEach { track ->
        add(
            SubtitleMenuItem.Embedded(
                trackId = track.id,
                label = subtitleTrackName(track),
                active = selection == SubtitleSelection.Embedded(track.id),
            ),
        )
    }
    external.forEach { subtitle ->
        add(
            SubtitleMenuItem.External(
                location = subtitle.location,
                label = subtitle.name,
                active = selection == SubtitleSelection.External(subtitle.location),
            ),
        )
    }
    add(SubtitleMenuItem.Off(active = selection == SubtitleSelection.Off))
}

/** 字幕按钮常显，不随是否有字幕轨或外挂字幕隐藏（ADR-0025）。 */
fun subtitleButtonVisible(subtitleTrackCount: Int, externalSubtitleCount: Int): Boolean = true

/** 内嵌轨道的显示名：语言与标题都在时并列，都没有时用轨道号。 */
fun subtitleTrackName(track: SubtitleTrack): String =
    listOfNotNull(track.language, track.title).joinToString(" ").ifEmpty { track.id.toString() }
