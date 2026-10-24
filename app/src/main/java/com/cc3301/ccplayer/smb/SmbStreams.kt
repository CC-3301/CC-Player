package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.streaming.ByteSource
import com.cc3301.ccplayer.streaming.LoopbackStreamServer
import com.cc3301.ccplayer.streaming.NetworkReadPriority
import com.cc3301.ccplayer.streaming.ReadKind
import kotlinx.coroutines.runBlocking

/**
 * 网络媒体播放接缝：把 SMB 位置映到本机回环地址，播放核心只拿到本机地址。
 *
 * 一个位置恒得同一个地址，播放列表里的每一项都能先换成地址；字节源在播放器读到时才建立。
 * [close] 一并关掉代理与全部已建立的流。
 */
class SmbStreams(
    private val connector: SmbConnector,
    private val locator: SmbLocator,
    private val server: LoopbackStreamServer = LoopbackStreamServer(),
) : NetworkStreams {

    /** 起播前试连一次；连不上或位置不属于任何书签时抛出来源失败异常。 */
    override suspend fun probe(location: String) {
        connectTo(location).second.close()
    }

    /** 位置对应的本机地址。 */
    override fun urlFor(location: String): String = server.serve(location) { openBlocking(location) }

    override fun close() {
        server.close()
    }

    /** 代理在自己的线程上取流，这里阻塞等连接与打开完成。 */
    private fun openBlocking(location: String): ByteSource = runBlocking {
        val (target, connection) = connectTo(location)
        try {
            NetworkReadPriority.shared.wrap(
                SmbStreamSource(connection, connection.openRead(target.path)),
                ReadKind.Playback,
            )
        } catch (failure: Exception) {
            runCatching { connection.close() }
            throw failure
        }
    }

    private suspend fun connectTo(location: String): Pair<SmbTarget, SmbConnection> {
        val target = locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
        val connection = connector.connect(
            host = target.host,
            port = target.port,
            share = target.share,
            username = target.username,
            password = target.password,
        )
        return target to connection
    }
}
