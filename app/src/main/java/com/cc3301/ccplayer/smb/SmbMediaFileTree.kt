package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaFileTree
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException

/**
 * SMB 文件树：位置经 [SmbLocator] 取到凭据后连上共享，再列举子项。
 *
 * 每次操作独立建连并在结束时关闭，不长期占用服务器会话。[resolve] 只看书签，不连服务器，
 * 否则服务器临时不可达时根目录会从列表里消失。
 */
class SmbMediaFileTree(
    private val connector: SmbConnector,
    private val locator: SmbLocator,
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
        return connector.connect(
            host = target.host,
            port = target.port,
            share = target.share,
            username = target.username,
            password = target.password,
        ).use { connection ->
            connection.list(target.path).map { entry ->
                entry.asMediaFile(SmbLocation(target.host, target.port, target.share, target.childPath(entry.name)).url)
            }
        }
    }
}

internal fun SmbEntry.asMediaFile(location: String): MediaFile = MediaFile(
    name = name,
    location = location,
    size = size,
    lastModified = lastModified,
    isDirectory = isDirectory,
    source = MediaSource.Network,
)
