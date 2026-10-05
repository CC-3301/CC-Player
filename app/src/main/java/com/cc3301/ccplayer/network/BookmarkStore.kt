package com.cc3301.ccplayer.network

import kotlinx.coroutines.flow.Flow

/** 书签非密码字段的持久化接缝；测试用内存实现。 */
interface BookmarkStore {

    val bookmarks: Flow<List<NetworkBookmark>>

    /** 整表覆盖保存。 */
    suspend fun save(bookmarks: List<NetworkBookmark>)
}
