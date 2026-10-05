package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaFileTree
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException

/**
 * WebDAV 文件树：位置经 [WebDavLocator] 取到凭据后发 PROPFIND 列举子项。
 *
 * [resolve] 只看书签，不请求服务器，否则服务器临时不可达时根目录会从列表里消失。
 */
class WebDavMediaFileTree(
    private val transport: WebDavTransport,
    private val locator: WebDavLocator,
) : MediaFileTree {

    override suspend fun resolve(location: String): MediaFile? {
        val target = locator.locate(location) ?: return null
        return MediaFile(
            name = target.name,
            location = location,
            isDirectory = true,
            source = MediaSource.Network,
        )
    }

    override suspend fun children(directory: MediaFile): List<MediaFile> {
        val target = locator.locate(directory.location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：${directory.location}")
        return transport.list(target.url, target.username, target.password).map { entry ->
            entry.asMediaFile(target.child(entry.name).url)
        }
    }
}

internal fun WebDavEntry.asMediaFile(location: String): MediaFile = MediaFile(
    name = name,
    location = location,
    size = size,
    lastModified = lastModified,
    isDirectory = isDirectory,
    source = MediaSource.Network,
)
