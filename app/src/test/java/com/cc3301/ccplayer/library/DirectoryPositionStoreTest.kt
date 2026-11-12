package com.cc3301.ccplayer.library

import androidx.datastore.preferences.core.edit
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.settings.FakePreferencesDataStore
import com.cc3301.ccplayer.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目录位置记录：按连接源与相对目录分别保存，删除连接源时整源清理。 */
class DirectoryPositionStoreTest {

    private val store = DataStoreDirectoryPositionStore(FakePreferencesDataStore())

    @Test
    fun `按连接源与相对目录分别记录与读回`() = runTest {
        val rootPosition = DirectoryPosition(entryLocation = "smb://nas/media/电影.mkv", scrollOffset = 0, sortOrder = SortOrder.NameAscending)
        val subPosition = DirectoryPosition(entryLocation = "smb://nas/media/剧集/第01集.mkv", scrollOffset = 84, sortOrder = SortOrder.NameAscending)

        store.save("smb://nas/media", "", rootPosition)
        store.save("smb://nas/media", "剧集", subPosition)

        assertEquals(rootPosition, store.load("smb://nas/media", ""))
        assertEquals(subPosition, store.load("smb://nas/media", "剧集"))
        assertNull(store.load("smb://nas/media", "电影"))
    }

    @Test
    fun `同一位置再次保存覆盖旧记录`() = runTest {
        store.save("content://root/媒体", "", DirectoryPosition("content://root/媒体/a.mkv", 10, SortOrder.NameAscending))
        store.save("content://root/媒体", "", DirectoryPosition("content://root/媒体/b.mkv", 32, SortOrder.NameAscending))

        assertEquals(DirectoryPosition("content://root/媒体/b.mkv", 32, SortOrder.NameAscending), store.load("content://root/媒体", ""))
    }

    @Test
    fun `连接源身份不同时互不覆盖`() = runTest {
        store.save("smb://nas/media", "", DirectoryPosition("smb://nas/media/a.mkv", 1, SortOrder.NameAscending))
        store.save("https://dav.example.com/cc", "", DirectoryPosition("https://dav.example.com/cc/a.mkv", 2, SortOrder.NameAscending))

        assertEquals("smb://nas/media/a.mkv", store.load("smb://nas/media", "")?.entryLocation)
        assertEquals("https://dav.example.com/cc/a.mkv", store.load("https://dav.example.com/cc", "")?.entryLocation)
    }

    @Test
    fun `删除连接源清理它的全部记录且不碰其他源`() = runTest {
        store.save("smb://nas/media", "", DirectoryPosition("smb://nas/media/a.mkv", 1, SortOrder.NameAscending))
        store.save("smb://nas/media", "剧集", DirectoryPosition("smb://nas/media/剧集/b.mkv", 2, SortOrder.NameAscending))
        store.save("smb://nas/other", "", DirectoryPosition("smb://nas/other/c.mkv", 3, SortOrder.NameAscending))

        store.clearSource("smb://nas/media")

        assertNull(store.load("smb://nas/media", ""))
        assertNull(store.load("smb://nas/media", "剧集"))
        assertEquals("smb://nas/other/c.mkv", store.load("smb://nas/other", "")?.entryLocation)
    }

    @Test
    fun `目录位置与设置和播放进度分开存放`() = runTest {
        val settingsStorage = FakePreferencesDataStore()
        val positionStorage = FakePreferencesDataStore()
        val settings = SettingsRepository(settingsStorage)
        val positionStore = DataStoreDirectoryPositionStore(positionStorage)

        settings.setDecodeMode(DecodeMode.Software)
        positionStore.save("smb://nas/media", "", DirectoryPosition("smb://nas/media/a.mkv", 12, SortOrder.NameAscending))

        assertEquals(DecodeMode.Software, settings.decodeMode.first())
        val settingsKeys = settingsStorage.data.first().asMap().keys.map { it.name }
        val positionKeys = positionStorage.data.first().asMap().keys.map { it.name }
        assertTrue(settingsKeys.none { it.startsWith("position_") })
        assertTrue(positionKeys.isNotEmpty())
        assertTrue(positionKeys.all { it.startsWith("position_") })
    }

    @Test
    fun `位置记录带上写入时的排序`() = runTest {
        val position = DirectoryPosition("smb://nas/media/b.mkv", 48, SortOrder.LastModifiedNewestFirst)

        store.save("smb://nas/media", "", position)

        assertEquals(position, store.load("smb://nas/media", ""))
    }

    @Test
    fun `没有排序的旧记录视为没有记录`() = runTest {
        val storage = FakePreferencesDataStore()
        storage.edit { preferences ->
            preferences[DataStoreDirectoryPositionStore.positionKey("smb://nas/media", "")] =
                "48\nsmb://nas/media/b.mkv"
        }

        assertNull(DataStoreDirectoryPositionStore(storage).load("smb://nas/media", ""))
    }

    @Test
    fun `相对目录层级按目录名拼接`() {
        val stack = listOf(
            testDirectory("media", "smb://nas/media"),
            testDirectory("剧集", "smb://nas/media/剧集"),
            testDirectory("第一季", "smb://nas/media/剧集/第一季"),
        )

        assertEquals("剧集/第一季", relativeDirectoryPath(stack))
        assertEquals("", relativeDirectoryPath(stack.take(1)))
    }
}
