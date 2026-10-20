package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.NetworkMediaReads
import com.cc3301.ccplayer.streaming.ByteSource

/**
 * SMB 预览读取：按书签连上共享后打开随机读句柄，不经过回环代理。
 *
 * 每次读取独立建连，句柄关闭时一并关闭会话。
 */
class SmbMediaReads(
    private val connector: SmbConnector,
    private val locator: SmbLocator,
) : NetworkMediaReads {

    override suspend fun openRead(location: String): ByteSource {
        val target = locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
        val connection = connector.connect(
            host = target.host,
            port = target.port,
            share = target.share,
            username = target.username,
            password = target.password,
        )
        return try {
            SmbStreamSource(connection, connection.openRead(target.path))
        } catch (failure: Exception) {
            runCatching { connection.close() }
            throw failure
        }
    }
}
