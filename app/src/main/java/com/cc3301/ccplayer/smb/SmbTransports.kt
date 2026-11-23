package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog

/**
 * 共享传输层：按（服务器、端口）复用一条底层连接，多条应用级会话共用。
 *
 * 关闭只有一个入口——引用计数归零：会话登记时占一个引用，会话关闭时交还，归零才关。
 * 本地传输失效的传输当场退出共享表，不再发给新的取用方；已持有引用的会话继续读到失败，
 * 传输在最后一个引用交还时关闭，因此浏览操作关不掉后台播放正在用的传输。
 * 播放读取租约按传输计：持有期间这条传输只能由播放自己的故障流程换掉。
 *
 * 并发建连时留先登记的那条，后到的那条当场关掉，最终收敛到一条；不为此加运行期检查。
 */
internal class SmbTransports(
    private val connector: SmbConnector,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) {

    private val lock = Any()

    /** 按服务器＋端口复用的传输。 */
    private val transports = HashMap<TransportKey, SharedTransport>()

    /** 取一条传输并占一个引用；表里没有就建一条。 */
    suspend fun acquire(host: String, port: Int): TransportLease {
        val key = TransportKey(host, port)
        val reused = synchronized(lock) { transports[key]?.also { it.inUse += 1 } }
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

    /** 交还一个引用；归零时退出共享表并关闭传输。 */
    private fun release(transport: SharedTransport) {
        val closing = synchronized(lock) {
            transport.inUse -= 1
            if (transport.inUse != 0) return
            val key = TransportKey(transport.host, transport.port)
            if (transports[key] === transport) transports.remove(key)
            transport
        }
        diagnostics.log { "SMB 传输关闭: ${closing.host}:${closing.port}" }
        closeQuietly(closing.connection)
    }

    /** 本地传输失效：退出共享表，不再发给新的取用方；最后一个引用交还时关闭。 */
    private fun discard(transport: SharedTransport) {
        synchronized(lock) {
            val key = TransportKey(transport.host, transport.port)
            if (transports[key] === transport) transports.remove(key)
        }
        diagnostics.log { "SMB 传输作废: ${transport.host}:${transport.port}" }
    }

    private fun closeQuietly(connection: SmbTransport) {
        runCatching { connection.close() }
    }

    private data class TransportKey(val host: String, val port: Int)

    /** 一条共享传输：底层连接、会话引用数与播放读取租约数。 */
    internal class SharedTransport(
        val host: String,
        val port: Int,
        val connection: SmbTransport,
    ) {
        /** 正在使用的引用数；每条会话占一个，不为 0 时不得关闭。 */
        var inUse = 0

        /** 播放读取租约数；不为 0 时浏览操作不得换掉这条传输。 */
        var playbackReads = 0
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
        val playbackLeaseHeld: Boolean get() = synchronized(lock) { transport.playbackReads > 0 }

        /** 在这条传输上认证并打开共享会话。 */
        suspend fun connectShare(share: String, username: String, password: String?): SmbConnection =
            transport.connection.connectShare(share, username, password)

        /** 播放读取登记：持有期间这条传输不因浏览操作的失败被换掉。 */
        fun beginPlaybackRead() {
            synchronized(lock) { transport.playbackReads += 1 }
        }

        /** 播放读取交还。 */
        fun endPlaybackRead() {
            synchronized(lock) { transport.playbackReads -= 1 }
        }

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
