package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.settings.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `默认开启自动滚动到上次播放位置`() {
        assertTrue(LibraryState().autoScrollToLastPlayed)
    }

    @Test
    fun `目录读完后把上次播放的条目定为滚动目标`() {
        val entries = listOf(entry("ep1.mkv", 1), entry("ep2.mkv", 2))

        val loaded = LibraryState(lastPlayedLocation = "root/ep2.mkv").withLoadedEntries(entries, failed = false)

        assertEquals("root/ep2.mkv", loaded.scrollTargetLocation)
        assertEquals(1, loaded.scrollRevision)
    }

    @Test
    fun `上次播放的条目不在当前目录时不滚动`() {
        val loaded = LibraryState(lastPlayedLocation = "root/别的/第01集.mkv")
            .withLoadedEntries(listOf(entry("ep1.mkv", 1)), failed = false)

        assertNull(loaded.scrollTargetLocation)
        assertEquals(0, loaded.scrollRevision)
    }

    @Test
    fun `自动滚动开关关时不滚`() {
        val loaded = LibraryState(lastPlayedLocation = "root/ep1.mkv", autoScrollToLastPlayed = false)
            .withLoadedEntries(listOf(entry("ep1.mkv", 1)), failed = false)

        assertNull(loaded.scrollTargetLocation)
        assertEquals(0, loaded.scrollRevision)
    }

    @Test
    fun `在播放器里切了集后重定当前目录的滚动目标`() {
        val entries = listOf(entry("ep1.mkv", 1), entry("ep2.mkv", 2))
        val loaded = LibraryState(lastPlayedLocation = "root/ep1.mkv").withLoadedEntries(entries, failed = false)

        val switched = loaded.copy(lastPlayedLocation = "root/ep2.mkv").withRefreshedScrollTarget()

        assertEquals("root/ep2.mkv", switched.scrollTargetLocation)
        assertEquals(2, switched.scrollRevision)
    }
}
