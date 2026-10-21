package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.DirectoryPosition
import com.cc3301.ccplayer.library.DirectoryScrollTarget
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
            state.withDisplayPreferences(
                viewMode = ViewMode.List,
                gridColumns = 3,
                sortOrder = SortOrder.NameAscending,
                autoRestorePosition = true,
            ).visibleEntries.map { it.name },
        )
    }

    @Test
    fun `默认开启位置记忆`() {
        assertTrue(LibraryState().autoRestorePosition)
    }

    @Test
    fun `目录读完后恢复记录的第一个可见条目与偏移`() {
        val entries = listOf(entry("ep1.mkv", 1), entry("ep2.mkv", 2), entry("ep3.mkv", 3))

        val loaded = LibraryState().withLoadedEntries(
            entries = entries,
            failure = null,
            position = DirectoryPosition(entryLocation = "root/ep2.mkv", scrollOffset = 64),
        )

        assertEquals(DirectoryScrollTarget(index = 1, offset = 64), loaded.scrollTarget)
        assertEquals(1, loaded.scrollRevision)
    }

    @Test
    fun `记录条目不存在时停在列表末尾`() {
        val entries = listOf(entry("ep1.mkv", 1), entry("ep2.mkv", 2), entry("ep3.mkv", 3))

        val loaded = LibraryState().withLoadedEntries(
            entries = entries,
            failure = null,
            position = DirectoryPosition(entryLocation = "root/已删除.mkv", scrollOffset = 40),
        )

        assertEquals(DirectoryScrollTarget(index = 2, offset = 0), loaded.scrollTarget)
    }

    @Test
    fun `关闭位置记忆后从顶部开始`() {
        val entries = listOf(entry("ep1.mkv", 1), entry("ep2.mkv", 2))

        val loaded = LibraryState(autoRestorePosition = false).withLoadedEntries(
            entries = entries,
            failure = null,
            position = DirectoryPosition(entryLocation = "root/ep2.mkv", scrollOffset = 64),
        )

        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), loaded.scrollTarget)
    }

    @Test
    fun `进程恢复强制按记录定位且不受位置记忆开关影响`() {
        val entries = listOf(entry("ep1.mkv", 1), entry("ep2.mkv", 2))
        val restored = LibraryState(restorePositionForced = true).withLoadedEntries(
            entries = entries,
            failure = null,
            position = DirectoryPosition(entryLocation = "root/ep2.mkv", scrollOffset = 48),
        )

        assertEquals(DirectoryScrollTarget(index = 1, offset = 48), restored.scrollTarget)

        val settingsApplied = restored.withDisplayPreferences(
            viewMode = restored.viewMode,
            gridColumns = restored.gridColumns,
            sortOrder = restored.sortOrder,
            autoRestorePosition = false,
        )
        assertEquals(DirectoryScrollTarget(index = 1, offset = 48), settingsApplied.scrollTarget)
    }

    @Test
    fun `没有记录时从顶部开始`() {
        val loaded = LibraryState().withLoadedEntries(
            entries = listOf(entry("ep1.mkv", 1)),
            failure = null,
            position = null,
        )

        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), loaded.scrollTarget)
    }

    @Test
    fun `空目录没有滚动目标`() {
        val loaded = LibraryState().withLoadedEntries(emptyList(), failure = null, position = null)

        assertNull(loaded.scrollTarget)
    }

    @Test
    fun `记录按可见次序定位而不是原始次序`() {
        val entries = listOf(entry("ep10.mkv", 2), entry("ep2.mkv", 1))

        val loaded = LibraryState().withLoadedEntries(
            entries = entries,
            failure = null,
            position = DirectoryPosition(entryLocation = "root/ep10.mkv", scrollOffset = 8),
        )

        assertEquals(DirectoryScrollTarget(index = 1, offset = 8), loaded.scrollTarget)
    }

    @Test
    fun `排序变化后回到顶部`() {
        val entries = listOf(entry("ep1.mkv", 1), entry("ep2.mkv", 2))
        val loaded = LibraryState().withLoadedEntries(
            entries = entries,
            failure = null,
            position = DirectoryPosition(entryLocation = "root/ep1.mkv", scrollOffset = 12),
        )

        val resorted = loaded.withDisplayPreferences(
            viewMode = ViewMode.List,
            gridColumns = 3,
            sortOrder = SortOrder.NameDescending,
            autoRestorePosition = true,
        )

        assertEquals(DirectoryScrollTarget(index = 0, offset = 0), resorted.scrollTarget)
        assertEquals(2, resorted.scrollRevision)
    }

    @Test
    fun `仅切换视图仍按记录条目定位`() {
        val entries = listOf(entry("ep1.mkv", 1), entry("ep2.mkv", 2))
        val loaded = LibraryState().withLoadedEntries(
            entries = entries,
            failure = null,
            position = DirectoryPosition(entryLocation = "root/ep2.mkv", scrollOffset = 24),
        )

        val switched = loaded.withDisplayPreferences(
            viewMode = ViewMode.Grid,
            gridColumns = 4,
            sortOrder = loaded.sortOrder,
            autoRestorePosition = true,
        )

        assertEquals(DirectoryScrollTarget(index = 1, offset = 24), switched.scrollTarget)
    }

    @Test
    fun `设置未变化时不重复触发滚动`() {
        val state = LibraryState().withLoadedEntries(listOf(entry("ep1.mkv", 1)), null, null)

        val unchanged = state.withDisplayPreferences(
            viewMode = state.viewMode,
            gridColumns = state.gridColumns,
            sortOrder = state.sortOrder,
            autoRestorePosition = state.autoRestorePosition,
        )

        assertEquals(state.scrollRevision, unchanged.scrollRevision)
    }

    private fun entry(name: String, lastModified: Long) =
        MediaFile(name = name, location = "root/$name", lastModified = lastModified)
}
