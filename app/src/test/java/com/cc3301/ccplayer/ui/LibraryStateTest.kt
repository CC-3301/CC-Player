package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.settings.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStateTest {

    @Test
    fun `默认名称升序与单列列表`() {
        val state = LibraryState()

        assertEquals(SortOrder.NameAscending, state.sortOrder)
        assertEquals(ViewMode.List, state.viewMode)
    }

    @Test
    fun `可见条目按当前排序选项排`() {
        val state = LibraryState(
            entries = listOf(entry("ep10.mkv", 2), entry("ep2.mkv", 1)),
            sortOrder = SortOrder.LastModifiedNewestFirst,
        )

        assertEquals(listOf("ep10.mkv", "ep2.mkv"), state.visibleEntries.map { it.name })
        assertEquals(
            listOf("ep2.mkv", "ep10.mkv"),
            state.withSortOrder(SortOrder.NameAscending).visibleEntries.map { it.name },
        )
    }

    @Test
    fun `排序选项变化时递增回到顶部的序号`() {
        val state = LibraryState()

        val changed = state.withSortOrder(SortOrder.NameDescending)
        val unchanged = changed.withSortOrder(SortOrder.NameDescending)

        assertEquals(1, changed.sortRevision)
        assertEquals(1, unchanged.sortRevision)
        assertEquals(2, unchanged.withSortOrder(SortOrder.LastModifiedOldestFirst).sortRevision)
    }

    private fun entry(name: String, lastModified: Long) =
        MediaFile(name = name, location = "root/$name", lastModified = lastModified)
}
