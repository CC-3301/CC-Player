package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.streaming.LoopbackStreamServer
import java.io.Closeable

/**
 * 网络媒体播放接缝：把 SMB 文件经本机回环代理暴露成 HTTP 地址，播放核心只拿到本机地址。
 *
 * 会话与读句柄随字节源一起关闭；[close] 一并关掉代理与全部已发出的流。
 */
class SmbStreams(
    private val connector: SmbConnector,
    private val locator: SmbLocator,
    private val server: LoopbackStreamServer = LoopbackStreamServer(),
) : Closeable {

    /**
     * 为位置开一条本机地址；位置不属于任何书签、指向目录或打不开时返回 null。
     */
    suspend fun urlFor(location: String): String? {
        val target = locator.locate(location) ?: return null
        if (target.path.isEmpty()) return null
        val connection = connector.connect(
            host = target.host,
            port = target.port,
            share = target.share,
            username = target.username,
            password = target.password,
        )
        val handle = try {
            connection.openRead(target.path)
        } catch (failure: Exception) {
            runCatching { connection.close() }
            throw failure
        }
        return try {
            server.serve(SmbStreamSource(connection, handle))
        } catch (failure: Exception) {
            runCatching { handle.close() }
            runCatching { connection.close() }
            throw failure
        }
    }

    override fun close() {
        server.close()
    }
}
