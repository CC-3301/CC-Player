package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 共享传输闲置宽限：引用归零后先留这么久再断开，期间重新取用继续用同一条连接。
 *
 * 取 5 秒，与建源总预算同一档，覆盖一次完整建源。
 */
private const val TRANSPORT_IDLE_GRACE_MILLIS = 5_000L

/**
 * 共享传输层：按（服务器、端口）复用一条底层连接，多条应用级会话共用。
 *
 * 关闭只有一个入口——引用计数归零：会话登记时占一个引用，会话关闭时交还。
 * 归零不立即断开，先进闲置宽限；宽限内被重新取用就继续用同一条，宽限到点仍没有引用才关。
 * 本地传输失效的传输当场退出共享表，不再发给新的取用方；已持有引用的会话继续读到失败，
 * 传输在最后一个引用交还时关闭，因此浏览操作关不掉后台播放正在用的传输。
 * 播放读取租约按服务器＋端口记（取用开始时传输可能还没建好）：持有期间这条传输只能由播放自己的故障流程换掉。
 *
 * 并发建连时留先登记的那条，后到的那条当场关掉，最终收敛到一条；不为此加运行期检查。
 */
internal class SmbTransports(
    private val connector: SmbConnector,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    private val scope: CoroutineScope,
) {

    private val lock = Any()

    /** 按服务器＋端口复用的传输。 */
    private val transports = HashMap<TransportKey, SharedTransport>()

    /**
     * 播放读取租约数，按服务器＋端口记：播放取用一开始就登记，那一刻传输可能还没建好，
     * 不能挂在某一份传输上。会话级的播放读取标记在会话层，两者由同一个租约对象一起占、一起还。
     */
    private val transportPlaybackReads = HashMap<TransportKey, Int>()

    /** 服务器＋端口级的播放读取登记：覆盖存活确认、会话重建、握手与打开读句柄全程。 */
    fun beginTransportPlaybackRead(host: String, port: Int) {
        synchronized(lock) { transportPlaybackReads.merge(TransportKey(host, port), 1, Int::plus) }
    }

    /** 服务器＋端口级的播放读取交还。 */
    fun endTransportPlaybackRead(host: String, port: Int) {
        synchronized(lock) {
            val key = TransportKey(host, port)
            val remaining = (transportPlaybackReads[key] ?: return) - 1
            if (remaining <= 0) transportPlaybackReads.remove(key) else transportPlaybackReads[key] = remaining
        }
    }

    /** 播放是否在这个服务器端口上有读取租约。 */
    private fun transportPlaybackHeld(host: String, port: Int): Boolean =
        synchronized(lock) { (transportPlaybackReads[TransportKey(host, port)] ?: 0) > 0 }

    /** 取一条传输并占一个引用；表里没有就建一条。宽限内的那条照样复用，不重开 TCP。 */
    suspend fun acquire(host: String, port: Int): TransportLease {
        val key = TransportKey(host, port)
        val reused = synchronized(lock) {
            transports[key]?.also {
                it.idleJob?.cancel()
                it.idleJob = null
                it.inUse += 1
            }
        }
        if (reused != null) return TransportLease(reused)
        val connection = connector.connectTransport(host, port)
        val adopted = synchronized(lock) {
            val current = transports[key]
            if (current != null) {
                current.inUse += 1
                current
            } else {
                SharedTransport(host, port, connection).also {
                    it.inUse = 1
                    transports[key] = it
                }
            }
        }
        if (adopted.connection !== connection) {
            closeQuietly(connection)
        } else {
            diagnostics.log { "SMB 传输建立: $host:$port" }
        }
        return TransportLease(adopted)
    }

    /**
     * 交还一个引用；归零时：本地传输失效的那条当场断开，其余先进闲置宽限。
     * 已作废的传输没有下一位取用方，留着只会多占一条连接。
     */
    private fun release(transport: SharedTransport) {
        val closing = synchronized(lock) {
            transport.inUse -= 1
            if (transport.inUse != 0) return
            if (transport.discarded) {
                takeTransportLocked(transport)
            } else {
                transport.idleJob?.cancel()
                transport.idleJob = scheduleIdleClose(transport)
                null
            }
        }
        if (closing != null) {
            diagnostics.log { "SMB 传输关闭: ${closing.host}:${closing.port}" }
            closeQuietly(closing.connection)
        }
    }

    /** 闲置宽限到点仍没有引用才断开；宽限期间开始的取用都在引用里，还在握手的也算。 */
    private fun scheduleIdleClose(transport: SharedTransport): Job = scope.launch {
        delay(TRANSPORT_IDLE_GRACE_MILLIS)
        val closing = synchronized(lock) {
            if (transport.inUse != 0) return@launch
            takeTransportLocked(transport)
        }
        diagnostics.log { "SMB 传输闲置关闭: ${closing.host}:${closing.port}" }
        closeQuietly(closing.connection)
    }

    /** 取出传输用于关闭：从共享表摘掉；表里已经是另一条时不动它。 */
    private fun takeTransportLocked(transport: SharedTransport): SharedTransport {
        val key = TransportKey(transport.host, transport.port)
        if (transports[key] === transport) transports.remove(key)
        transport.idleJob?.cancel()
        transport.idleJob = null
        return transport
    }

    /** 本地传输失效：退出共享表，不再发给新的取用方；最后一个引用交还时关闭。 */
    private fun discard(transport: SharedTransport) {
        synchronized(lock) {
            transport.discarded = true
            val key = TransportKey(transport.host, transport.port)
            if (transports[key] === transport) transports.remove(key)
        }
        diagnostics.log { "SMB 传输作废: ${transport.host}:${transport.port}" }
    }

    private fun closeQuietly(connection: SmbTransport) {
        runCatching { connection.close() }
    }

    private data class TransportKey(val host: String, val port: Int)

    /** 一条共享传输：底层连接与会话引用数。 */
    internal class SharedTransport(
        val host: String,
        val port: Int,
        val connection: SmbTransport,
    ) {
        /** 正在使用的引用数；每条会话占一个，不为 0 时不得关闭。 */
        var inUse = 0

        /** 等待中的闲置断开任务；宽限内被重新取用时取消。 */
        var idleJob: Job? = null

        /** 本地传输失效，已退出共享表：引用归零当场断开，不再进闲置宽限。 */
        var discarded = false
    }

    /**
     * 一条共享传输的占用：登记时占一个引用，交还时归零才关。
     *
     * 同一时刻可以有多份占用指向同一条传输（不同共享或凭据的会话），它们看到同一份传输状态：
     * 播放读取租约记在传输上，不记在某一份占用上。
     */
    inner class TransportLease internal constructor(private val transport: SharedTransport) {

        private var released = false

        /** 播放是否在这条传输上持有读取租约。 */
        val playbackLeaseHeld: Boolean get() = transportPlaybackHeld(transport.host, transport.port)

        /** 在这条传输上认证并打开共享会话。 */
        suspend fun connectShare(share: String, username: String, password: String?): SmbConnection =
            transport.connection.connectShare(share, username, password)

        /** 本地传输失效：退出共享表，不再发给新的取用方。 */
        fun discard() = this@SmbTransports.discard(transport)

        /** 交还引用；归零时关闭传输。 */
        fun release() {
            synchronized(lock) {
                if (released) return
                released = true
            }
            this@SmbTransports.release(transport)
        }
    }
}
