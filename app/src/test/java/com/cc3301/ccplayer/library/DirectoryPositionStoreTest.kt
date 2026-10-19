package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.settings.FakePreferencesDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 目录位置记录：按连接源与相对目录分别保存，删除连接源时整源清理。 */
class DirectoryPositionStoreTest {

    private val store = DataStoreDirectoryPositionStore(FakePreferencesDataStore())

    @Test
    fun `按连接源与相对目录分别记录与读回`() = runTest {
        val rootPosition = DirectoryPosition(entryLocation = "smb://nas/media/电影.mkv", scrollOffset = 0)
        val subPosition = DirectoryPosition(entryLocation = "smb://nas/media/剧集/第01集.mkv", scrollOffset = 84)

        store.save("smb://nas/media", "", rootPosition)
        store.save("smb://nas/media", "剧集", subPosition)

        assertEquals(rootPosition, store.load("smb://nas/media", ""))
        assertEquals(subPosition, store.load("smb://nas/media", "剧集"))
        assertNull(store.load("smb://nas/media", "电影"))
    }

    @Test
    fun `同一位置再次保存覆盖旧记录`() = runTest {
        store.save("content://root/媒体", "", DirectoryPosition("content://root/媒体/a.mkv", 10))
        store.save("content://root/媒体", "", DirectoryPosition("content://root/媒体/b.mkv", 32))

        assertEquals(DirectoryPosition("content://root/媒体/b.mkv", 32), store.load("content://root/媒体", ""))
    }

    @Test
    fun `连接源身份不同时互不覆盖`() = runTest {
        store.save("smb://nas/media", "", DirectoryPosition("smb://nas/media/a.mkv", 1))
        store.save("https://dav.example.com/cc", "", DirectoryPosition("https://dav.example.com/cc/a.mkv", 2))

        assertEquals("smb://nas/media/a.mkv", store.load("smb://nas/media", "")?.entryLocation)
        assertEquals("https://dav.example.com/cc/a.mkv", store.load("https://dav.example.com/cc", "")?.entryLocation)
    }

    @Test
    fun `删除连接源清理它的全部记录且不碰其他源`() = runTest {
        store.save("smb://nas/media", "", DirectoryPosition("smb://nas/media/a.mkv", 1))
        store.save("smb://nas/media", "剧集", DirectoryPosition("smb://nas/media/剧集/b.mkv", 2))
        store.save("smb://nas/other", "", DirectoryPosition("smb://nas/other/c.mkv", 3))

        store.clearSource("smb://nas/media")

        assertNull(store.load("smb://nas/media", ""))
        assertNull(store.load("smb://nas/media", "剧集"))
        assertEquals("smb://nas/other/c.mkv", store.load("smb://nas/other", "")?.entryLocation)
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
