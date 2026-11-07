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

    /** 已登记陈旧：不在这里注销句柄，等下一次探测或取用时在播放线程上关掉。 */
    private var preparedStale = false

    /** 起播前试连一次：打开读句柄并留给播放复用；连不上或位置不属于任何书签时抛出来源失败异常。 */
    override suspend fun probe(location: String) {
        val target = targetOf(location)
        urlFor(location)
        // 这条流已经有可用的源：播放会直接复用它，再存一条没人取的句柄只会等过期。
        if (server.hasFreshSource(location)) return
        val handle = transport.openRead(target.url, target.username, target.password, size = 0)
        handOff(location, handle)
    }

    /** 位置对应的本机地址。 */
    override fun urlFor(location: String): String = server.serve(location) { openBlocking(location) }

    /** 只打标记不注销句柄：可能在被主线程调用，这里不碰 I/O。 */
    override fun discardByteSources() {
        synchronized(handoff) { preparedStale = true }
        server.discardSources()
    }

    /** 归还播放源：探测预置的句柄不在这条路径上，它由 [handOff] 覆盖、[discardPreparedSource] 丢弃、[close] 收尾。 */
    override fun returnSource(location: String) {
        server.returnSource(location)
    }

    /** 取消打开：探测预置的句柄当场丢掉，不等下一次探测或关闭。 */
    override fun discardPreparedSource() {
        closePrepared()
    }

    override fun close() {
        closePrepared()
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
            preparedStale = false
            previous
        }
        if (superseded != null && superseded !== source) runCatching { superseded.close() }
    }

    /** 取出该位置留好的句柄；位置不符、已登记陈旧或已取过时先关掉留下的那条。 */
    private fun takePrepared(location: String): ByteSource? {
        val matched: ByteSource?
        val stale: ByteSource?
        synchronized(handoff) {
            val usable = preparedLocation == location && !preparedStale
            matched = if (usable) preparedSource else null
            stale = if (usable) null else preparedSource
            preparedLocation = null
            preparedSource = null
            preparedStale = false
        }
        stale?.let { source -> runCatching { source.close() } }
        return matched
    }

    private fun closePrepared() {
        val source = synchronized(handoff) {
            val previous = preparedSource
            preparedLocation = null
            preparedSource = null
            preparedStale = false
            previous
        }
        source?.let { runCatching { it.close() } }
    }

    private suspend fun targetOf(location: String): WebDavTarget =
        locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
}
