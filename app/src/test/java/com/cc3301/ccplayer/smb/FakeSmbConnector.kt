package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay

/**
 * 假连接器：用「共享 + 路径 → 子项」的映射代替真 SMB 服务器。
 * 共享传输与会话是两层：一次 connectTransport 建一条传输，会话建在传输上，关会话不断传输。
 * 并发测试里连接与关闭来自多条线程，记录事件的列表用写时复制。
 */
class FakeSmbConnector : SmbConnector {

    var failure: MediaSourceException? = null

    /** 列目录时抛出的异常；非空时按 [listFailureTimes] 次数失败。 */
    var listFailure: Throwable? = null

    /** 还要抛几次列目录失败；默认一直抛，用来驱动「失败一次后自动恢复成功」。 */
    var listFailureTimes: Int = Int.MAX_VALUE

    /** 列举目录时执行；测试用它在中途占住会话，观察关闭权与传输淘汰。 */
    var duringList: (suspend () -> Unit)? = null

    /** 打开读句柄时执行；测试用它在中途占住会话，观察关闭权与传输淘汰。 */
    var duringOpenRead: (suspend () -> Unit)? = null

    /** 每次连接用到的用户名与密码。 */
    val logins = CopyOnWriteArrayList<Pair<String, String?>>()

    /** 已建立会话时给的共享名。 */
    val shares = CopyOnWriteArrayList<String>()

    /** 服务器共享枚举返回的名称。 */
    var shareNames: List<String> = emptyList()

    /** 服务器协商的单次读取上限。 */
    var maxReadSize: Int = 0

    /** 打开读句柄时抛出的异常；非空时每次打开都失败。随时可改，连接沿用最新的。 */
    var openReadFailure: MediaSourceException? = null

    /** 读取句柄时抛出的异常；非空时每次读取都失败。随时可改，连接沿用最新的。 */
    var readFailure: Throwable? = null

    /** 建连尝试次数：失败也计数。 */
    var connectAttempts: Int = 0
        private set

    /** 还要抛几次读取失败；默认一直抛，用来驱动「失败一次后重发成功」。 */
    var readFailureTimes: Int = Int.MAX_VALUE

    /** 存活确认抛出的异常；非空时视为会话已失效。 */
    var pingFailure: Throwable? = null

    /** 建连耗时：用来驱动会话建立预算。 */
    var connectDelayMillis: Long = 0

    /** 建连期间执行；测试用它把建连卡住，观察关闭是否等重连。 */
    var duringConnect: (() -> Unit)? = null

    /** 存活确认的次数。 */
    var pings: Int = 0
        private set

    /** 最近一次存活确认给的上限。 */
    var lastPingTimeout: Long = 0
        private set

    /** 建过的共享传输，一次 connectTransport 一条。 */
    val transports = CopyOnWriteArrayList<FakeSmbTransport>()

    /** 被关掉的传输，按「主机:端口」。 */
    val closedTransports = CopyOnWriteArrayList<String>()

    private val childrenByPath = mutableMapOf<String, List<SmbEntry>>()
    private val bytesByPath = mutableMapOf<String, ByteArray>()

    /** 已打开的读句柄，按路径。 */
    val handles = mutableMapOf<String, RecordingSmbHandle>()

    /** 依次打开过的全部读句柄，用来断言关闭次数。 */
    val allHandles = mutableListOf<RecordingSmbHandle>()

    /** 连接与句柄的关闭顺序。 */
    val closeOrder = CopyOnWriteArrayList<String>()

    /** 被关掉的会话属于哪个共享，用来断言淘汰选中了哪一条会话。 */
    val closedShares = CopyOnWriteArrayList<String>()

    fun addDirectory(share: String, path: String, vararg children: SmbEntry): FakeSmbConnector {
        childrenByPath["$share/$path"] = children.toList()
        return this
    }

    fun addFile(share: String, path: String, bytes: ByteArray): FakeSmbConnector {
        bytesByPath["$share/$path"] = bytes
        return this
    }

    override suspend fun shares(
        host: String,
        port: Int,
        username: String,
        password: String?,
    ): List<String> {
        failure?.let { throw it }
        logins += username to password
        return shareNames
    }

    override suspend fun connectTransport(host: String, port: Int): SmbTransport {
        connectAttempts++
        failure?.let { throw it }
        if (connectDelayMillis > 0) delay(connectDelayMillis)
        duringConnect?.invoke()
        return FakeSmbTransport(host, port).also { transports += it }
    }

    /** 一条假传输：会话建在它上面；关掉它说明这条共享传输没了。 */
    inner class FakeSmbTransport(
        val host: String,
        val port: Int,
    ) : SmbTransport {

        /** 这条传输是否被关掉。 */
        var closed = false
            private set

        override suspend fun connectShare(share: String, username: String, password: String?): SmbConnection {
            logins += username to password
            shares += share
            return FakeSmbConnection(share)
        }

        override fun close() {
            closed = true
            closedTransports += "$host:$port"
            closeOrder += "transport"
        }
    }

    /** 一条假会话：关它只记录会话关闭，不动传输。 */
    private inner class FakeSmbConnection(private val share: String) : SmbConnection {

        override suspend fun list(path: String): List<SmbEntry> {
            duringList?.invoke()
            listFailure?.let { failure ->
                if (listFailureTimes <= 0) return childrenByPath["$share/$path"].orEmpty()
                listFailureTimes -= 1
                throw failure
            }
            return childrenByPath["$share/$path"].orEmpty()
        }

        override suspend fun openRead(path: String): SmbReadHandle {
            duringOpenRead?.invoke()
            openReadFailure?.let { throw it }
            val bytes = bytesByPath["$share/$path"]
                ?: throw MediaSourceException(MediaFailure.NotFound, "没有这个文件：$path")
            return RecordingSmbHandle(
                bytes,
                maxReadSize = maxReadSize,
                onClose = { closeOrder += "handle" },
                readFailure = {
                    if (readFailureTimes <= 0) {
                        null
                    } else {
                        readFailureTimes--
                        readFailure
                    }
                },
            ).also {
                handles["$share/$path"] = it
                allHandles += it
            }
        }

        override suspend fun ping(timeoutMillis: Long) {
            pings++
            lastPingTimeout = timeoutMillis
            pingFailure?.let { throw it }
        }

        override fun close() {
            closeOrder += "session"
            closedShares += share
        }
    }
}

/** 服务器报的会话级失效，用来驱动「只重建会话、传输继续用」。 */
fun sessionLostFailure(): SMBApiException =
    SMBApiException(NtStatus.STATUS_USER_SESSION_DELETED.value, SMB2MessageCommandCode.SMB2_ECHO, null)

/** 记录是否被关闭的读句柄。 */
class RecordingSmbHandle(
    private val bytes: ByteArray,
    override val maxReadSize: Int = 0,
    private val onClose: () -> Unit = {},
    private val readFailure: () -> Throwable? = { null },
) : SmbReadHandle, Closeable {

    var closed = false

    /** 被关掉的次数：同一句柄只该关一次。 */
    var closeCount = 0
        private set

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        readFailure()?.let { throw it }
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closeCount++
        closed = true
        onClose()
    }
}

fun directoryEntry(name: String, size: Long = 0, lastModified: Long = 0): SmbEntry =
    SmbEntry(name = name, isDirectory = true, size = size, lastModified = lastModified)

fun fileEntry(name: String, size: Long = 1024, lastModified: Long = 1_700_000_000_000): SmbEntry =
    SmbEntry(name = name, isDirectory = false, size = size, lastModified = lastModified)
