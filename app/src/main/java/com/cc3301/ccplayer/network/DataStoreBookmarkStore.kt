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
 * 书签非密码字段的持久化：一条书签一行，字段以制表符分隔并转义。
 *
 * 旧版本只存 `地址<TAB>用户名`；读到这种行时按地址拆成协议、服务器地址与路径。
 */
class DataStoreBookmarkStore(private val dataStore: DataStore<Preferences>) : BookmarkStore {

    override val bookmarks: Flow<List<NetworkBookmark>> = dataStore.data.map { preferences ->
        preferences[BOOKMARKS].orEmpty().lines().filter { it.isNotBlank() }.mapNotNull(::decodeBookmark)
    }

    /** 整表覆盖已知书签；认不出的行原样保留，避免后续保存把旧数据抹掉。 */
    override suspend fun save(bookmarks: List<NetworkBookmark>) {
        dataStore.edit { preferences ->
            val unknownLines = preferences[BOOKMARKS].orEmpty().lines()
                .filter { it.isNotBlank() && decodeBookmark(it) == null }
            val saved = bookmarks.map(::encodeBookmark) + unknownLines
            preferences[BOOKMARKS] = saved.joinToString("\n")
        }
    }

    private fun decodeBookmark(line: String): NetworkBookmark? =
        if (line.startsWith(LEGACY_SMB_PREFIX) || line.startsWith(LEGACY_HTTP_PREFIX) || line.startsWith(LEGACY_HTTPS_PREFIX)) {
            NetworkBookmark.fromUrl(
                url = line.substringBefore(FIELD_SEPARATOR),
                username = line.substringAfter(FIELD_SEPARATOR, ""),
            )
        } else {
            bookmarkOf(decodeFields(line))
        }

    private fun encodeBookmark(bookmark: NetworkBookmark): String =
        listOf(bookmark.protocol.name, bookmark.name, bookmark.address, bookmark.path, bookmark.username)
            .joinToString(FIELD_SEPARATOR.toString()) { encodeField(it) }

    private fun bookmarkOf(fields: List<String>): NetworkBookmark? {
        if (fields.size < FIELD_COUNT) return null
        val protocol = SourceProtocol.entries.firstOrNull { it.name == fields[0] && it != SourceProtocol.Local } ?: return null
        return NetworkBookmark(
            protocol = protocol,
            name = fields[1],
            address = fields[2],
            path = fields[3],
            username = fields[4],
        )
    }

    /** 转义反斜杠、制表符与换行，保证一条书签占一行且字段边界稳定。 */
    private fun encodeField(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                ESCAPE -> append(ESCAPE).append(ESCAPE)
                FIELD_SEPARATOR -> append(ESCAPE).append('t')
                '\n' -> append(ESCAPE).append('n')
                '\r' -> append(ESCAPE).append('r')
                else -> append(character)
            }
        }
    }

    private fun decodeFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == ESCAPE && index + 1 < line.length -> {
                    when (val escaped = line[index + 1]) {
                        't' -> current.append(FIELD_SEPARATOR)
                        'n' -> current.append('\n')
                        'r' -> current.append('\r')
                        else -> current.append(escaped)
                    }
                    index += 2
                }

                character == FIELD_SEPARATOR -> {
                    fields += current.toString()
                    current.clear()
                    index++
                }

                else -> {
                    current.append(character)
                    index++
                }
            }
        }
        fields += current.toString()
        return fields
    }

    companion object {
        private val BOOKMARKS = stringPreferencesKey("bookmarks")
        private const val FIELD_SEPARATOR = '\t'
        private const val ESCAPE = '\\'
        private const val FIELD_COUNT = 5
        private const val LEGACY_SMB_PREFIX = "smb://"
        private const val LEGACY_HTTP_PREFIX = "http://"
        private const val LEGACY_HTTPS_PREFIX = "https://"

        fun create(context: Context): DataStoreBookmarkStore =
            DataStoreBookmarkStore(context.applicationContext.bookmarksDataStore)
    }
}
