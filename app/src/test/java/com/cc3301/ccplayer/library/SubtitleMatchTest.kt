package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** 表驱动：外挂字幕的同名前缀匹配与评分排序。 */
class SubtitleMatchTest {

    @Test
    fun `完全同名得分最高，带语言后缀次之，同分按名称自然排序`() {
        val entries = listOf(
            entry("ep01.cht.ass"),
            entry("封面.jpg"),
            entry("ep01.chs.srt"),
            entry("ep01.srt"),
        )

        assertEquals(
            listOf("ep01.srt", "ep01.chs.srt", "ep01.cht.ass"),
            matchExternalSubtitles("ep01.mkv", entries).map { it.name },
        )
    }

    @Test
    fun `大小写不区分`() {
        assertEquals(
            listOf("EP01.CHS.ASS"),
            matchExternalSubtitles("Ep01.MKV", listOf(entry("EP01.CHS.ASS"))).map { it.name },
        )
    }

    @Test
    fun `地区码后缀参与评分`() {
        val entries = listOf(entry("ep01.zh-CN.ass"), entry("ep01.zh-TW.ass"))

        assertEquals(
            listOf("ep01.zh-CN.ass", "ep01.zh-TW.ass"),
            matchExternalSubtitles("ep01.mkv", entries).map { it.name },
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
    fun `别的片子的字幕不入选`() {
        val entries = listOf(
            entry("ep02.chs.ass"),
            entry("ep01x.ass"),
            entry("ep0102.chs.ass"),
        )

        assertEquals(emptyList<String>(), matchExternalSubtitles("ep01.mkv", entries).map { it.name })
    }

    @Test
    fun `扩展名限 srt 与 ass`() {
        val entries = listOf(entry("ep01.vtt"), entry("ep01.sub"), entry("ep01.txt"), entry("ep01idx.srt"))

        assertEquals(emptyList<String>(), matchExternalSubtitles("ep01.mkv", entries).map { it.name })
    }

    @Test
    fun `语言后缀表之外的后缀不入选`() {
        assertEquals(
            emptyList<String>(),
            matchExternalSubtitles("ep01.mkv", listOf(entry("ep01.unknown.ass"))).map { it.name },
        )
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
