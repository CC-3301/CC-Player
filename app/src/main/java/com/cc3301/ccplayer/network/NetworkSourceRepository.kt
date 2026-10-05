package com.cc3301.ccplayer.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 网络源集合：书签的增删查与按地址取密码。与具体协议无关，各协议共用同一套。
 */
class NetworkSourceRepository(
    private val store: BookmarkStore,
    private val credentials: CredentialStore,
) {

    val bookmarks: Flow<List<NetworkBookmark>> = store.bookmarks

    /** 全部网络源的根位置，顺序即添加顺序。 */
    val locations: Flow<List<String>> = store.bookmarks.map { bookmarks -> bookmarks.map { it.url } }

    /** 新增或更新一条书签：同一地址只保留一条。 */
    suspend fun add(url: String, username: String, password: String) {
        val bookmarks = store.bookmarks.first()
        store.save(bookmarks.filterNot { it.url == url } + NetworkBookmark(url = url, username = username))
        credentials.put(url, password)
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
