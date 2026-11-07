package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.streaming.ByteSource

/**
 * SMB 预览读取：按书签取到共享内路径，再从会话层取一条会话打开随机读句柄，不经过回环代理。
 *
 * 会话与目录浏览、播放共用同一条；句柄关闭只交还会话占用，不关会话。
 */
class SmbMediaReads(
    private val sessions: SmbSessions,
    private val locator: SmbLocator,
) : NetworkMediaReads {

    override suspend fun openRead(location: String, size: Long): ByteSource {
        val target = locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
        return sessions.openRead(target)
    }
}
