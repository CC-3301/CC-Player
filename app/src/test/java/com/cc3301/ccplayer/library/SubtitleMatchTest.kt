package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 表驱动：外挂字幕的同名前缀匹配与评分排序。 */
class SubtitleMatchTest {

    @Test
    fun `完全同名得 2 分，同名前缀带后缀得 1 分`() {
        assertEquals(2, subtitleMatchScore("ep01.mkv", "ep01.srt"))
        assertEquals(1, subtitleMatchScore("ep01.mkv", "ep01.chs.srt"))
        assertEquals(1, subtitleMatchScore("ep01.mkv", "ep01.director.ass"))
    }

    @Test
    fun `三种分隔符都算同名前缀的边界`() {
        assertEquals(1, subtitleMatchScore("ep01.mkv", "ep01.chs.ass"))
        assertEquals(1, subtitleMatchScore("ep01.mkv", "ep01_chs.srt"))
        assertEquals(1, subtitleMatchScore("ep01.mkv", "ep01-director.ass"))
    }

    @Test
    fun `中文名的同名前缀入选`() {
        assertEquals(1, subtitleMatchScore("影片.mkv", "影片.简体.ass"))
    }

    @Test
    fun `表外后缀与多段后缀都入选`() {
        assertEquals(1, subtitleMatchScore("ep01.mkv", "ep01.unknown.ass"))
        assertEquals(1, subtitleMatchScore("ep01.mkv", "ep01.chs.director.srt"))
    }

    @Test
    fun `完全同名得分最高，同分按名称自然排序`() {
        val entries = listOf(
            entry("ep01.cht.ass"),
            entry("封面.jpg"),
            entry("ep01.chs.srt"),
            entry("ep01.srt"),
            entry("ep01.director.ass"),
        )

        assertEquals(
            listOf("ep01.srt", "ep01.chs.srt", "ep01.cht.ass", "ep01.director.ass"),
            matchExternalSubtitles("ep01.mkv", entries).map { it.name },
        )
    }

    @Test
    fun `多候选按名称自然排序`() {
        val entries = listOf(
            entry("ep01.10.srt"),
            entry("ep01.9.srt"),
            entry("ep01.2.srt"),
        )

        assertEquals(
            listOf("ep01.2.srt", "ep01.9.srt", "ep01.10.srt"),
            matchExternalSubtitles("ep01.mkv", entries).map { it.name },
        )
    }

    @Test
    fun `大小写不区分`() {
        assertEquals(2, subtitleMatchScore("Ep01.MKV", "EP01.SRT"))
        assertEquals(1, subtitleMatchScore("Ep01.MKV", "EP01.CHS.ASS"))
        assertEquals(
            listOf("EP01.CHS.ASS"),
            matchExternalSubtitles("Ep01.MKV", listOf(entry("EP01.CHS.ASS"))).map { it.name },
        )
    }

    @Test
    fun `多段媒体名用最后一个点之前的部分做前缀`() {
        val entries = listOf(entry("剧集.第01集.chs.ass"))

        assertEquals(
            listOf("剧集.第01集.chs.ass"),
            matchExternalSubtitles("剧集.第01集.mkv", entries).map { it.name },
        )
    }

    @Test
    fun `前缀不符的名字不入选`() {
        assertNull(subtitleMatchScore("ep01.mkv", "ep01x.ass"))
        assertNull(subtitleMatchScore("ep01.mkv", "ep0102.chs.ass"))
        assertNull(subtitleMatchScore("ep01.mkv", "ep02.chs.ass"))
        assertEquals(
            emptyList<String>(),
            matchExternalSubtitles(
                "ep01.mkv",
                listOf(entry("ep01x.ass"), entry("ep0102.chs.ass"), entry("ep02.chs.ass")),
            ).map { it.name },
        )
    }

    @Test
    fun `扩展名限 srt 与 ass`() {
        val entries = listOf(entry("ep01.vtt"), entry("ep01.sub"), entry("ep01.txt"), entry("ep01idx.srt"))

        assertNull(subtitleMatchScore("ep01.mkv", "ep01.vtt"))
        assertEquals(emptyList<String>(), matchExternalSubtitles("ep01.mkv", entries).map { it.name })
    }

    @Test
    fun `目录条目与非字幕文件不入选`() {
        val entries = listOf(
            testDirectory("ep01.ass", "root/ep01.ass"),
            testDirectory("字幕", "root/字幕"),
            entry("封面.jpg"),
        )

        assertEquals(emptyList<String>(), matchExternalSubtitles("ep01.mkv", entries).map { it.name })
    }

    @Test
    fun `没有同目录条目时没有候选`() {
        assertEquals(emptyList<String>(), matchExternalSubtitles("ep01.mkv", emptyList()).map { it.name })
    }

    private fun entry(name: String): MediaFile = testDocument(name, "root/$name")
}
