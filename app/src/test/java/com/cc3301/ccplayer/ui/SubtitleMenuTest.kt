package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.playback.SubtitleSelection
import com.cc3301.ccplayer.playback.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/** 字幕菜单构成：内嵌轨道 + 可匹配外挂 + 关闭，当前生效项标出来。 */
class SubtitleMenuTest {

    private val chinese = SubtitleTrack(id = 3, language = "chs", title = "简体")
    private val english = SubtitleTrack(id = 5, language = "eng", title = null)
    private val externalChinese = document("ep01.chs.ass")
    private val externalPlain = document("ep01.srt")

    @Test
    fun `菜单依次列内嵌轨道、可匹配外挂与关闭`() {
        val items = subtitleMenuItems(
            listOf(chinese, english),
            listOf(externalPlain, externalChinese),
            SubtitleSelection.Embedded(3),
        )

        assertEquals(
            listOf(
                SubtitleMenuItem.Embedded(3, "chs 简体", active = true),
                SubtitleMenuItem.Embedded(5, "eng", active = false),
                SubtitleMenuItem.External("root/ep01.srt", "ep01.srt", active = false),
                SubtitleMenuItem.External("root/ep01.chs.ass", "ep01.chs.ass", active = false),
                SubtitleMenuItem.Off(active = false),
            ),
            items,
        )
    }

    @Test
    fun `选外挂时外挂项生效，内嵌项都不生效`() {
        val items = subtitleMenuItems(listOf(chinese), listOf(externalPlain), SubtitleSelection.External("root/ep01.srt"))

        assertEquals(listOf(false, true, false), items.map { it.active })
    }

    @Test
    fun `关闭时只有关闭项生效`() {
        val items = subtitleMenuItems(listOf(chinese), listOf(externalPlain), SubtitleSelection.Off)

        assertEquals(listOf(false, false, true), items.map { it.active })
    }

    @Test
    fun `没有字幕时菜单只剩关闭`() {
        val items = subtitleMenuItems(emptyList(), emptyList(), SubtitleSelection.Off)

        assertEquals(listOf(SubtitleMenuItem.Off(active = true)), items)
    }

    @Test
    fun `不在候选里的外挂不被标为生效`() {
        val items = subtitleMenuItems(emptyList(), listOf(externalPlain), SubtitleSelection.External("root/ep02.srt"))

        assertEquals(listOf(false, false), items.map { it.active })
    }

    @Test
    fun `内嵌轨道没有语言与标题时用轨道号`() {
        assertEquals("7", subtitleTrackName(SubtitleTrack(id = 7, language = null, title = null)))
        assertEquals("简体", subtitleTrackName(SubtitleTrack(id = 7, language = null, title = "简体")))
    }

    private fun document(name: String): MediaFile = MediaFile(name = name, location = "root/$name")
}
