package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.network.NetworkStreams
import com.cc3301.ccplayer.network.PreparedSource
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

    /** 探测打开、等播放第一个请求取用的句柄与它的所有者；同一时刻只有一个位置。 */
    private var preparedLocation: String? = null
    private var preparedSlot: Handoff? = null

    /** 已登记陈旧：不在这里注销句柄，等下一次探测或取用时在播放线程上关掉。 */
    private var preparedStale = false

    override fun prepare(location: String): PreparedSource = Handoff(location)

    /** 位置对应的本机地址。 */
    override fun urlFor(location: String): String = server.serve(location) { openBlocking(location) }

    /** 只打标记不注销句柄：可能在被主线程调用，这里不碰 I/O。 */
    override fun discardByteSources() {
        synchronized(handoff) { preparedStale = true }
        server.discardSources()
    }

    /** 归还播放源：探测预置的句柄不在这条路径上，它由 [Handoff] 的作废与 [close] 收尾。 */
    override fun returnSource(location: String) {
        server.returnSource(location)
    }

    override fun close() {
        closePrepared()
        server.close()
    }

    /**
     * 一次准备的所有权：登记的句柄、作废标记都归这一个对象。
     * 作废只丢自己登记的句柄；在途打开回来时看到已作废就自己关掉；槽里换成别人那次准备的句柄不动。
     * 关闭都派到关源线程，界面线程不碰 I/O。
     */
    private inner class Handoff(val location: String) : PreparedSource {

        private val lock = Any()

        /** 已登记、等播放取用的句柄。 */
        private var source: ByteSource? = null

        /** 本次准备已作废：之后的打开回来不再登记。 */
        private var discarded = false

        /** 播放取走这条句柄；之后这次准备不再持有它。调用方不得持 [handoff] 锁进入。 */
        fun take(): ByteSource? = synchronized(lock) {
            val current = source
            source = null
            discarded = true
            current
        }

        /** 在这次准备名下试连一次：打开读句柄并留给播放复用。 */
        override suspend fun probe(location: String) {
            val target = targetOf(location)
            urlFor(location)
            // 这条流已经有可用的源：播放会直接复用它，再存一条没人取的句柄只会等过期。
            if (server.hasFreshSource(location)) return
            handOff(transport.openRead(target.url, target.username, target.password, size = 0))
        }

        /** 打开完成：登记到预置槽；顶替掉的旧句柄与已作废时的这条新句柄都在关源线程关闭。 */
        private fun handOff(opened: ByteSource) {
            var superseded: ByteSource? = null
            val cancelled = synchronized(lock) {
                if (discarded) {
                    true
                } else {
                    superseded = source
                    source = opened
                    false
                }
            }
            if (cancelled) {
                server.closeInBackground(opened)
                return
            }
            // 登记同样在出锁之后做：锁内不进 [handoff] 锁。
            // 出锁到登记之间可能被作废：已作废就不再登记，免得槽里留一条空凭证。
            val cancelledMeanwhile = synchronized(lock) { discarded }
            if (cancelledMeanwhile) {
                server.closeInBackground(opened)
                return
            }
            attach(this)?.discard()
            superseded?.let { previous -> if (previous !== opened) server.closeInBackground(previous) }
        }

        override fun discard() {
            val mine = synchronized(lock) {
                discarded = true
                val current = source
                source = null
                current
            }
            // 摘槽在出锁之后：锁序恒为「先 handoff，后 lock」，任何一把锁内都不进另一把。
            detach(this)
            mine?.let { current -> server.closeInBackground(current) }
        }
    }

    /** 把这次准备登记到预置槽上，返回被顶替的那次准备；它的句柄由调用方丢弃。 */
    private fun attach(slot: Handoff): Handoff? = synchronized(handoff) {
        val previous = preparedSlot?.takeIf { it !== slot }
        preparedLocation = slot.location
        preparedSlot = slot
        preparedStale = false
        previous
    }

    /** 这次准备不再是槽里的那条：别人的登记不受影响。 */
    private fun detach(slot: Handoff) {
        synchronized(handoff) {
            if (preparedSlot !== slot) return
            preparedLocation = null
            preparedSlot = null
            preparedStale = false
        }
    }

    /** 代理在自己的线程上取流，这里阻塞等打开完成；探测留好的句柄优先复用。 */
    private fun openBlocking(location: String): ByteSource = runBlocking {
        val prepared = takePrepared(location)
        val source = prepared ?: targetOf(location).let { target ->
            transport.openRead(target.url, target.username, target.password, size = 0)
        }
        NetworkReadPriority.shared.wrap(source, ReadKind.Playback)
    }

    /**
     * 取出该位置留好的句柄；位置不符、已登记陈旧或已取过时先关掉留下的那条。
     * 槽引用在 [handoff] 锁内取出，句柄在出锁之后交给 [Handoff.take]，两把锁不嵌套。
     */
    private fun takePrepared(location: String): ByteSource? {
        val usable: Handoff?
        val leftover: Handoff?
        synchronized(handoff) {
            val slot = preparedSlot
            usable = slot?.takeIf { preparedLocation == location && !preparedStale }
            leftover = slot?.takeIf { usable == null }
            preparedLocation = null
            preparedSlot = null
            preparedStale = false
        }
        leftover?.discard()
        return usable?.take()
    }

    private fun closePrepared() {
        val slot = synchronized(handoff) {
            val current = preparedSlot
            preparedLocation = null
            preparedSlot = null
            preparedStale = false
            current
        }
        slot?.discard()
    }

    private suspend fun targetOf(location: String): WebDavTarget =
        locator.locate(location)
            ?: throw MediaSourceException(MediaFailure.NotFound, "没有对应的网络源书签：$location")
}
