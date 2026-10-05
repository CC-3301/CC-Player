package com.cc3301.ccplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSortTest {

    private val series = testDirectory("剧集", "root/剧集")
    private val movies = testDirectory("电影", "root/电影")

    private val entries = listOf(
        entry("旧.mkv", 1_600_000_000_000),
        series,
        entry("ep10.mkv", 1_900_000_000_000),
        entry("ep2.mkv", 1_750_000_000_000),
        movies,
        entry("ep02.mkv", 1_750_000_000_000),
    )

    @Test
    fun `名称升序：子目录在前，组内自然排序`() {
        val names = entries.sortedForDisplay(SortOrder.NameAscending).map { it.name }

        assertEquals(listOf("剧集", "电影", "ep2.mkv", "ep02.mkv", "ep10.mkv", "旧.mkv"), names)
    }

    @Test
    fun `名称降序：子目录仍在前，组内次序反过来`() {
        val names = entries.sortedForDisplay(SortOrder.NameDescending).map { it.name }

        assertEquals(listOf("电影", "剧集", "旧.mkv", "ep10.mkv", "ep02.mkv", "ep2.mkv"), names)
    }

    @Test
    fun `修改时间新在前：子目录仍在前`() {
        val names = entries.sortedForDisplay(SortOrder.LastModifiedNewestFirst).map { it.name }

        assertEquals(listOf("剧集", "电影", "ep10.mkv", "ep2.mkv", "ep02.mkv", "旧.mkv"), names)
    }

    @Test
    fun `修改时间旧在前：子目录仍在前`() {
        val names = entries.sortedForDisplay(SortOrder.LastModifiedOldestFirst).map { it.name }

        assertEquals(listOf("剧集", "电影", "旧.mkv", "ep2.mkv", "ep02.mkv", "ep10.mkv"), names)
    }

    @Test
    fun `按修改时间排序时时间相同用名称自然排序兜底`() {
        val sameTime = listOf(entry("ep10.mkv", 7), entry("ep02.mkv", 7), entry("ep2.mkv", 7))

        assertEquals(
            listOf("ep2.mkv", "ep02.mkv", "ep10.mkv"),
            sameTime.sortedForDisplay(SortOrder.LastModifiedNewestFirst).map { it.name },
        )
        assertEquals(
            listOf("ep2.mkv", "ep02.mkv", "ep10.mkv"),
            sameTime.sortedForDisplay(SortOrder.LastModifiedOldestFirst).map { it.name },
        )
    }

    @Test
    fun `排序不改动原列表`() {
        val unordered = listOf(entry("b.mkv", 2), entry("a.mkv", 1))

        unordered.sortedForDisplay(SortOrder.NameAscending)

        assertEquals(listOf("b.mkv", "a.mkv"), unordered.map { it.name })
    }

    private fun entry(name: String, lastModified: Long) =
        MediaFile(name = name, location = "root/$name", lastModified = lastModified)
}
