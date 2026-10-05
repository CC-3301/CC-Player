package com.cc3301.ccplayer.network

import com.cc3301.ccplayer.settings.FakePreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreBookmarkStoreTest {

    private val store = DataStoreBookmarkStore(FakePreferencesDataStore())

    @Test
    fun `默认没有书签`() = runTest {
        assertEquals(emptyList<NetworkBookmark>(), store.bookmarks.first())
    }

    @Test
    fun `书签写入后按顺序读回`() = runTest {
        val bookmarks = listOf(
            NetworkBookmark(url = "smb://192.168.1.10/media", username = "cc"),
            NetworkBookmark(url = "smb://192.168.1.10/剧集/第二季", username = ""),
        )

        store.save(bookmarks)

        assertEquals(bookmarks, store.bookmarks.first())
    }

    @Test
    fun `用户名含制表符也能读回`() = runTest {
        val bookmarks = listOf(NetworkBookmark(url = "smb://nas/media", username = "dom\tuser"))

        store.save(bookmarks)

        assertEquals(bookmarks, store.bookmarks.first())
    }

    @Test
    fun `保存空列表清空书签`() = runTest {
        store.save(listOf(NetworkBookmark(url = "smb://nas/media", username = "cc")))

        store.save(emptyList())

        assertEquals(emptyList<NetworkBookmark>(), store.bookmarks.first())
    }
}
