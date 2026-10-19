package com.cc3301.ccplayer.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 网络源集合：书签的增删改查与按地址取密码。与具体协议无关，SMB 与 WebDAV 共用同一套。
 */
class NetworkSourceRepository(
    private val store: BookmarkStore,
    private val credentials: CredentialStore,
) {

    val bookmarks: Flow<List<NetworkBookmark>> = store.bookmarks

    /** 新增一条书签：同一地址只保留一条，顺序即添加顺序。 */
    suspend fun add(bookmark: NetworkBookmark, password: String) {
        val bookmarks = store.bookmarks.first()
        store.save(bookmarks.filterNot { it.url == bookmark.url } + bookmark)
        credentials.put(bookmark.url, password)
    }

    /** 用 [bookmark] 替换 [previousUrl] 对应的书签；地址变了就把密码搬到新地址下。 */
    suspend fun update(previousUrl: String, bookmark: NetworkBookmark, password: String) {
        val bookmarks = store.bookmarks.first()
        val previousIndex = bookmarks.indexOfFirst { it.url == previousUrl }
        val others = bookmarks.filterNot { it.url == previousUrl || it.url == bookmark.url }
        val updated = others.toMutableList().apply {
            add(previousIndex.coerceIn(0, size), bookmark)
        }
        store.save(updated)
        if (previousUrl != bookmark.url) credentials.remove(previousUrl)
        credentials.put(bookmark.url, password)
    }

    suspend fun remove(url: String) {
        store.save(store.bookmarks.first().filterNot { it.url == url })
        credentials.remove(url)
    }

    suspend fun password(url: String): String? = credentials.get(url)

    /** 找位置所属的书签：地址相同或为其子路径，重叠时取最长的地址。 */
    suspend fun find(location: String): NetworkBookmark? =
        store.bookmarks.first()
            .filter { location == it.url || location.startsWith("${it.url}/") }
            .maxByOrNull { it.url.length }
}
