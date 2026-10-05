package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.network.NetworkSourceRepository

/**
 * 把位置翻成 SMB 访问参数：书签决定主机、共享与凭据，位置相对书签的余下部分决定共享内路径。
 */
class SmbLocator(private val sources: NetworkSourceRepository) {

    /** 位置不属于任何书签或书签地址不合法时返回 null。 */
    suspend fun locate(location: String): SmbTarget? {
        val bookmark = sources.find(location) ?: return null
        val root = SmbLocation.parse(bookmark.url) ?: return null
        val suffix = location.removePrefix(bookmark.url).trim('/')
        return SmbTarget(
            host = root.host,
            port = root.port,
            share = root.share,
            path = root.path.appended(suffix),
            username = bookmark.username,
            password = sources.password(bookmark.url),
        )
    }
}

private fun String.appended(segment: String): String = when {
    segment.isEmpty() -> this
    isEmpty() -> segment
    else -> "$this/$segment"
}
