package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaFileTree
import com.cc3301.ccplayer.library.MediaSource
import com.cc3301.ccplayer.library.MediaSourceException

/**
 * SMB 文件树：位置经 [SmbLocator] 取到凭据后从会话层取会话列举子项。
 *
 * 会话与封面、播放共用同一条，闲置回收由会话层负责。[resolve] 只看书签，不连服务器，
 * 否则服务器临时不可达时根目录会从列表里消失。服务器根只列可浏览的共享，隐藏的管理共享不列。
 */
class SmbMediaFileTree(
    private val sessions: SmbSessions,
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
        val entries = if (target.share.isEmpty()) {
            sessions.shares(
                host = target.host,
                port = target.port,
                username = target.username,
                password = target.password,
            ).filterNot { share -> share.endsWith("$") }
                .map { share -> SmbEntry(name = share, isDirectory = true, size = 0, lastModified = 0) }
        } else {
            sessions.use(target) { connection -> connection.list(target.path) }
        }
        return entries.map { entry -> entry.asMediaFile(target.childLocation(entry.name)) }
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
