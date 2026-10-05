package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.testDocument
import org.junit.Assert.assertEquals
import org.junit.Test

/** 表驱动：起播时加载哪几路外挂、显示哪一路字幕。 */
class SubtitleStartTest {

    private val firstEmbedded = SubtitleTrack(id = 3, language = "chs", title = "简体")
    private val secondEmbedded = SubtitleTrack(id = 5, language = "eng", title = null)

    @Test
    fun `有内嵌也有外挂：外挂全部加载，显示内嵌`() {
        val decision = subtitleStartDecision("ep01.mkv", listOf(firstEmbedded, secondEmbedded), entries("ep01.chs.ass", "ep01.srt"))

        assertEquals(listOf("ep01.srt", "ep01.chs.ass"), decision.externalToLoad.map { it.name })
        assertEquals(SubtitleSelection.Embedded(3), decision.selection)
    }

    @Test
    fun `有内嵌没有外挂：显示内嵌，没有要加载的外挂`() {
        val decision = subtitleStartDecision("ep01.mkv", listOf(firstEmbedded), entries("封面.jpg"))

        assertEquals(emptyList<MediaFile>(), decision.externalToLoad)
        assertEquals(SubtitleSelection.Embedded(3), decision.selection)
    }

    @Test
    fun `没有内嵌有外挂：显示得分最高的那条`() {
        val decision = subtitleStartDecision("ep01.mkv", emptyList(), entries("ep01.cht.ass", "ep01.srt"))

        assertEquals(listOf("ep01.srt", "ep01.cht.ass"), decision.externalToLoad.map { it.name })
        assertEquals(SubtitleSelection.External("root/ep01.srt"), decision.selection)
    }

    @Test
    fun `内嵌与外挂都没有：不显示字幕`() {
        val decision = subtitleStartDecision("ep01.mkv", emptyList(), entries("ep02.chs.ass"))

        assertEquals(emptyList<MediaFile>(), decision.externalToLoad)
        assertEquals(SubtitleSelection.Off, decision.selection)
    }

    @Test
    fun `内嵌轨道取第一条`() {
        val decision = subtitleStartDecision("ep01.mkv", listOf(firstEmbedded, secondEmbedded), emptyList())

        assertEquals(SubtitleSelection.Embedded(3), decision.selection)
    }

    private fun entries(vararg names: String): List<MediaFile> = names.map { testDocument(it, "root/$it") }
}
