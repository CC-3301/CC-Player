package com.cc3301.ccplayer.network

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
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
            NetworkBookmark(SourceProtocol.Smb, name = "NAS", address = "192.168.1.10", path = "media", username = "cc"),
            NetworkBookmark(SourceProtocol.WebDav, name = "网盘", address = "https://dav.example.com", path = "剧集/第二季", username = ""),
        )

        store.save(bookmarks)

        assertEquals(bookmarks, store.bookmarks.first())
    }

    @Test
    fun `名称与用户名含制表符换行也能读回`() = runTest {
        val bookmarks = listOf(
            NetworkBookmark(SourceProtocol.Smb, name = "书房\tNAS", address = "nas", path = "media", username = "dom\tuser\ncc"),
        )

        store.save(bookmarks)

        assertEquals(bookmarks, store.bookmarks.first())
    }

    @Test
    fun `保存空列表清空书签`() = runTest {
        store.save(listOf(NetworkBookmark(SourceProtocol.Smb, "NAS", "nas", "media", "cc")))

        store.save(emptyList())

        assertEquals(emptyList<NetworkBookmark>(), store.bookmarks.first())
    }

    @Test
    fun `旧 SMB 书签迁移成服务器地址与路径`() = runTest {
        val legacy = legacyStore("smb://192.168.1.10/media/剧集\tcc")

        assertEquals(
            listOf(NetworkBookmark(SourceProtocol.Smb, name = "", address = "192.168.1.10", path = "media/剧集", username = "cc")),
            legacy.bookmarks.first(),
        )
    }

    @Test
    fun `旧 WebDAV 书签迁移成服务器地址与起始目录`() = runTest {
        val legacy = legacyStore("https://dav.example.com/dav/files/cc\t")

        assertEquals(
            listOf(NetworkBookmark(SourceProtocol.WebDav, name = "", address = "https://dav.example.com", path = "dav/files/cc", username = "")),
            legacy.bookmarks.first(),
        )
    }

    @Test
    fun `旧用户名里的反斜杠与制表符原样保留`() = runTest {
        val legacy = legacyStore("smb://nas/media\tDOM\\cc\tadmin")

        assertEquals("DOM\\cc\tadmin", legacy.bookmarks.first().single().username)
    }

    private fun legacyStore(line: String): DataStoreBookmarkStore = DataStoreBookmarkStore(
        FakePreferencesDataStore(preferencesOf(stringPreferencesKey("bookmarks") to line)),
    )
}
