package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.streaming.ByteSource
import com.cc3301.ccplayer.streaming.LoopbackStreamServer
import com.cc3301.ccplayer.streaming.NetworkReadPriority
import com.cc3301.ccplayer.streaming.ReadKind
import kotlinx.coroutines.runBlocking

/**
 * 网络媒体播放接缝：把 WebDAV 位置映到本机回环地址，播放核心只拿到本机地址。
 *
 * 一个位置恒得同一个地址，播放列表里的每一项都能先换成地址；字节源在播放器读到时才建立。
 * 探测打开的读句柄交给播放的第一个请求复用，探测不再产生一次被丢弃的连接。
 * [close] 一并关掉代理与全部已建立的流。
 */
class WebDavStreams(
    private val transport: WebDavTransport,
    private val locator: WebDavLocator,
    diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    private val server: LoopbackStreamServer = LoopbackStreamServer(diagnostics = diagnostics),
) : NetworkStreams {

    private val handoff = Any()

    /** 探测打开、等播放第一个请求取用的读句柄；同一时刻只有一个位置。 */
    private var preparedLocation: String? = null
    private var preparedSource: ByteSource? = null

    /** 起播前试连一次：打开读句柄并留给播放复用；连不上或位置不属于任何书签时抛出来源失败异常。 */
    override suspend fun probe(location: String) {
        val target = targetOf(location)
        // 播放读取没有目录列表的大小，句柄打开时探测一次长度。
        val handle = transport.openRead(target.url, target.username, target.password, size = 0)
        handOff(location, handle)
    }

    /** 位置对应的本机地址。 */
    override fun urlFor(location: String): String = server.serve(location) { openBlocking(location) }

    override fun discardByteSources() {
        discardPrepared()
        server.discardSources()
    }

    override fun close() {
        discardPrepared()
        server.close()
    }

    /** 代理在自己的线程上取流，这里阻塞等打开完成；探测留好的句柄优先复用。 */
    private fun openBlocking(location: String): ByteSource = runBlocking {
        val prepared = takePrepared(location)
        val source = prepared ?: targetOf(location).let { target ->
            transport.openRead(target.url, target.username, target.password, size = 0)
        }
        NetworkReadPriority.shared.wrap(source, ReadKind.Playback)
    }

    private fun handOff(location: String, source: ByteSource) {
        val superseded = synchronized(handoff) {
            val previous = preparedSource
            preparedLocation = location
            preparedSource = source
            previous
        }
        if (superseded != null && superseded !== source) runCatching { superseded.close() }
    }

    /** 取出该位置留好的句柄；位置不符或已取过时关闭留下的那条并返回 null。 */
    private fun takePrepared(location: String): ByteSource? {
        val matched: ByteSource?
        val stale: ByteSource?
        synchronized(handoff) {
            val sameLocation = preparedLocation == location
            matched = if (sameLocation) preparedSource else null
            stale = if (sameLocation) null else preparedSource
            preparedLocation = null
            preparedSource = null
        }
        stale?.let { source -> runCatching { source.close() } }
        return matched
    }

    private fun discardPrepared() {
        val source = synchronized(handoff) {
            val previous = preparedSource
            preparedLocation = null
            preparedSource = null
            previous
        }
        source?.let { runCatching { it.close() } }
    }

    private suspend fun targetOf(location: String): WebDavTarget =
        locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
}
