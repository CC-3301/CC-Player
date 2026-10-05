package com.cc3301.ccplayer.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bookmarksDataStore: DataStore<Preferences> by preferencesDataStore(name = "network_bookmarks")

/**
 * 书签非密码字段的持久化：一条书签一行 `地址<TAB>用户名`。地址不含换行与制表符。
 */
class DataStoreBookmarkStore(private val dataStore: DataStore<Preferences>) : BookmarkStore {

    override val bookmarks: Flow<List<NetworkBookmark>> = dataStore.data.map { preferences ->
        preferences[BOOKMARKS].orEmpty().lines().filter { it.isNotBlank() }.map { line ->
            NetworkBookmark(
                url = line.substringBefore(FIELD_SEPARATOR),
                username = line.substringAfter(FIELD_SEPARATOR, ""),
            )
        }
    }

    override suspend fun save(bookmarks: List<NetworkBookmark>) {
        dataStore.edit { preferences ->
            preferences[BOOKMARKS] = bookmarks.joinToString("\n") { "${it.url}$FIELD_SEPARATOR${it.username}" }
        }
    }

    companion object {
        private val BOOKMARKS = stringPreferencesKey("bookmarks")
        private const val FIELD_SEPARATOR = "\t"

        fun create(context: Context): DataStoreBookmarkStore =
            DataStoreBookmarkStore(context.applicationContext.bookmarksDataStore)
    }
}
