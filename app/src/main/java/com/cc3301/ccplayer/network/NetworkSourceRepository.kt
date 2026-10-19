package com.cc3301.ccplayer.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

/**
 * 网络源集合：书签的增删改查与按地址取密码。与具体协议无关，SMB 与 WebDAV 共用同一套。
 */
class NetworkSourceRepository(
    private val store: BookmarkStore,
    private val credentials: CredentialStore,
) {

    /** 每次读书签顺手把旧地址（多一个尾斜杠）下的密码搬到当前地址。 */
    val bookmarks: Flow<List<NetworkBookmark>> = store.bookmarks.onEach { bookmarks ->
        bookmarks.forEach { bookmark -> migrateLegacyPassword(bookmark.url) }
    }

    /** 新增一条书签：同一地址只保留一条，顺序即添加顺序。 */
    suspend fun add(bookmark: NetworkBookmark, password: String) {
        val bookmarks = store.bookmarks.first()
        store.save(bookmarks.filterNot { it.url == bookmark.url } + bookmark)
        credentials.put(bookmark.url, password)
        legacyCredentialKey(bookmark.url)?.let { credentials.remove(it) }
    }

    /**
     * 用 [bookmark] 替换 [previousUrl] 对应的书签；地址变了就把密码搬到新地址下。
     *
     * 目标地址已被另一条书签占用时抛 [IllegalArgumentException]，不静默覆盖。
     */
    suspend fun update(previousUrl: String, bookmark: NetworkBookmark, password: String) {
        val bookmarks = store.bookmarks.first()
        if (bookmarks.any { it.url == bookmark.url && it.url != previousUrl }) {
            throw IllegalArgumentException("地址已存在：${bookmark.url}")
        }
        val previousIndex = bookmarks.indexOfFirst { it.url == previousUrl }
        val others = bookmarks.filterNot { it.url == previousUrl }
        val updated = others.toMutableList().apply {
            add(previousIndex.coerceIn(0, size), bookmark)
        }
        store.save(updated)
        if (previousUrl != bookmark.url) {
            removeCredentials(previousUrl)
        }
        credentials.put(bookmark.url, password)
        legacyCredentialKey(bookmark.url)?.let { credentials.remove(it) }
    }

    suspend fun remove(url: String) {
        store.save(store.bookmarks.first().filterNot { it.url == url })
        removeCredentials(url)
    }

    suspend fun password(url: String): String? {
        migrateLegacyPassword(url)
        return credentials.get(url)
    }

    /** 找位置所属的书签：地址相同或为其子路径，重叠时取最长的地址。 */
    suspend fun find(location: String): NetworkBookmark? =
        store.bookmarks.first()
            .filter { location == it.url || location.startsWith("${it.url}/") }
            .maxByOrNull { it.url.length }

    /** 旧版本的 SMB 地址可能多一个尾斜杠，密码因此存在旧键下。 */
    private suspend fun migrateLegacyPassword(url: String) {
        if (credentials.get(url) != null) return
        val legacyKey = legacyCredentialKey(url) ?: return
        val secret = credentials.get(legacyKey) ?: return
        credentials.put(url, secret)
        credentials.remove(legacyKey)
    }

    private suspend fun removeCredentials(url: String) {
        credentials.remove(url)
        legacyCredentialKey(url)?.let { credentials.remove(it) }
    }

    private fun legacyCredentialKey(url: String): String? =
        "${url.trimEnd('/')}/".takeIf { it != url }
}
