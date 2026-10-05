package com.cc3301.ccplayer.network

/** 密码存储接缝：实现落在加密存储上，测试用内存实现代替。 */
interface CredentialStore {

    suspend fun put(key: String, secret: String)

    suspend fun get(key: String): String?

    suspend fun remove(key: String)
}
