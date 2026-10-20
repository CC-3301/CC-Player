package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.streaming.ByteSource
import com.cc3301.ccplayer.streaming.LoopbackStreamServer
import kotlinx.coroutines.runBlocking

/**
 * 网络媒体播放接缝：把 WebDAV 位置映到本机回环地址，播放核心只拿到本机地址。
 *
 * 一个位置恒得同一个地址，播放列表里的每一项都能先换成地址；字节源在播放器读到时才建立。
 * [close] 一并关掉代理与全部已建立的流。
 */
class WebDavStreams(
    private val transport: WebDavTransport,
    private val locator: WebDavLocator,
    private val server: LoopbackStreamServer = LoopbackStreamServer(),
) : NetworkStreams {

    /** 起播前试连一次；连不上或位置不属于任何书签时抛出来源失败异常。 */
    override suspend fun probe(location: String) {
        val target = targetOf(location)
        transport.openRead(target.url, target.username, target.password).close()
    }

    /** 位置对应的本机地址。 */
    override fun urlFor(location: String): String = server.serve(location) { openBlocking(location) }

    /** 预览抽取用的随机读句柄：直接读源站，不经过回环代理。 */
    override suspend fun openRead(location: String): ByteSource {
        val target = targetOf(location)
        return transport.openRead(target.url, target.username, target.password)
    }

    override fun close() {
        server.close()
    }

    /** 代理在自己的线程上取流，这里阻塞等打开完成。 */
    private fun openBlocking(location: String): ByteSource = runBlocking { openRead(location) }

    private suspend fun targetOf(location: String): WebDavTarget =
        locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
}
