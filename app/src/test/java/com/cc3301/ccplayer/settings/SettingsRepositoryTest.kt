package com.cc3301.ccplayer.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.cc3301.ccplayer.library.SortOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsRepositoryTest {

    private val dataStore = FakePreferencesDataStore()
    private val settings = SettingsRepository(dataStore)

    @Test
    fun `默认单列列表、3 列网格与名称升序`() = runTest {
        assertEquals(ViewMode.List, settings.viewMode.first())
        assertEquals(3, settings.gridColumns.first())
        assertEquals(SortOrder.NameAscending, settings.sortOrder.first())
    }

    @Test
    fun `网格视图与列数写入后读回`() = runTest {
        settings.setViewMode(ViewMode.Grid)
        settings.setGridColumns(4)

        assertEquals(ViewMode.Grid, settings.viewMode.first())
        assertEquals(4, settings.gridColumns.first())
    }

    @Test
    fun `四种排序偏好写入后读回`() = runTest {
        SortOrder.entries.forEach { order ->
            settings.setSortOrder(order)

            assertEquals(order, settings.sortOrder.first())
        }
    }

    @Test
    fun `网格列数只接受 2 到 4`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { settings.setGridColumns(5) }
        }
    }

    @Test
    fun `持久化的列数超出 2 到 4 时回落默认 3 列`() = runTest {
        dataStore.edit { preferences -> preferences[intPreferencesKey("grid_columns")] = 5 }

        assertEquals(3, settings.gridColumns.first())
    }

    @Test
    fun `根目录按添加顺序保存且不重复`() = runTest {
        settings.addRoot("content://root/a")
        settings.addRoot("content://root/b")
        settings.addRoot("content://root/a")

        assertEquals(listOf("content://root/a", "content://root/b"), settings.rootLocations.first())
    }
}
