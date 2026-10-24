package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.library.networkSourceKey
import com.cc3301.ccplayer.streaming.ByteSource

/**
 * WebDAV 预览读取：按书签取到访问参数后打开随机读句柄，不经过回环代理。
 *
 * 句柄按需发带 Range 的 GET；关闭句柄即关闭响应。
 */
class WebDavMediaReads(
    private val transport: WebDavTransport,
    private val locator: WebDavLocator,
) : NetworkMediaReads {

    override suspend fun openRead(location: String): ByteSource {
        val target = locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
        return transport.openRead(target.url, target.username, target.password)
    }

    override suspend fun sourceKey(location: String): String =
        locator.sourceKey(location) ?: networkSourceKey(location)
}
