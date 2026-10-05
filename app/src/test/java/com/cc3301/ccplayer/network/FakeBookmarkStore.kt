package com.cc3301.ccplayer.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** 内存书签存储；[saved] 记录最近一次落盘的内容，供断言密码没有混入。 */
class FakeBookmarkStore : BookmarkStore {

    private val state = MutableStateFlow<List<NetworkBookmark>>(emptyList())

    var saved: List<NetworkBookmark> = emptyList()
        private set

    override val bookmarks: Flow<List<NetworkBookmark>> get() = state

    override suspend fun save(bookmarks: List<NetworkBookmark>) {
        saved = bookmarks
        state.value = bookmarks
    }

    suspend fun current(): List<NetworkBookmark> = state.first()
}

/** 内存密码存储。 */
class FakeCredentialStore : CredentialStore {

    private val secrets = mutableMapOf<String, String>()

    override suspend fun put(key: String, secret: String) {
        secrets[key] = secret
    }

    override suspend fun get(key: String): String? = secrets[key]

    override suspend fun remove(key: String) {
        secrets.remove(key)
    }
}
