package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.network.NetworkSourceRepository

/**
 * 把位置翻成 WebDAV 访问参数：书签决定源地址与凭据，位置相对书签的余下部分决定源内路径。
 */
class WebDavLocator(private val sources: NetworkSourceRepository) {

    /** 位置所属书签的地址；缩略图并发按书签分组。 */
    suspend fun sourceKey(location: String): String? = sources.find(location)?.url

    /** 位置不属于任何书签或书签地址不合法时返回 null。 */
    suspend fun locate(location: String): WebDavTarget? {
        val bookmark = sources.find(location) ?: return null
        val root = WebDavAddress.normalize(bookmark.url) ?: return null
        return WebDavTarget(
            root = root,
            path = location.removePrefix(bookmark.url).trim('/'),
            username = bookmark.username,
            password = sources.password(bookmark.url),
        )
    }
}
