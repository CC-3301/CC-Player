package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import java.io.Closeable
import kotlinx.coroutines.delay

/** 假连接器：用「共享 + 路径 → 子项」的映射代替真 SMB 服务器。 */
class FakeSmbConnector : SmbConnector {

    var failure: MediaSourceException? = null

    /** 列目录时抛出的异常；非空时每次列举都失败。 */
    var listFailure: Throwable? = null

    /** 每次连接用到的用户名与密码。 */
    val logins = mutableListOf<Pair<String, String?>>()

    /** 已建立连接时给的共享名。 */
    val shares = mutableListOf<String>()

    /** 服务器共享枚举返回的名称。 */
    var shareNames: List<String> = emptyList()

    /** 服务器协商的单次读取上限。 */
    var maxReadSize: Int = 0

    /** 打开读句柄时抛出的异常；非空时每次打开都失败。随时可改，连接沿用最新的。 */
    var openReadFailure: MediaSourceException? = null

    /** 读取句柄时抛出的异常；非空时每次读取都失败。随时可改，连接沿用最新的。 */
    var readFailure: Throwable? = null

    /** 读取前按位置执行，用来把某一次读卡在中间。 */
    var readGate: ((Long) -> Unit)? = null

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

    /** 句柄的打开与关闭顺序。 */
    val openClose = mutableListOf<String>()

    private val childrenByPath = mutableMapOf<String, List<SmbEntry>>()
    private val bytesByPath = mutableMapOf<String, ByteArray>()

    /** 已打开的读句柄，按路径。 */
    val handles = mutableMapOf<String, RecordingSmbHandle>()

    /** 依次打开过的全部读句柄，用来断言关闭次数。 */
    val allHandles = mutableListOf<RecordingSmbHandle>()

    /** 连接与句柄的关闭顺序。 */
    val closeOrder = mutableListOf<String>()

    /** 被关掉的连接属于哪个共享，用来断言淘汰选中了哪一条会话。 */
    val closedShares = mutableListOf<String>()

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

    override suspend fun connect(
        host: String,
        port: Int,
        share: String,
        username: String,
        password: String?,
    ): SmbConnection {
        connectAttempts++
        failure?.let { throw it }
        if (connectDelayMillis > 0) delay(connectDelayMillis)
        duringConnect?.invoke()
        logins += username to password
        shares += share
        return FakeSmbConnection(
            share = share,
            childrenByPath = childrenByPath,
            bytesByPath = bytesByPath,
            handles = handles,
            allHandles = allHandles,
            openClose = openClose,
            closeOrder = closeOrder,
            closedShares = closedShares,
            maxReadSize = maxReadSize,
            openReadFailure = { openReadFailure },
            listFailure = { listFailure },
            readFailure = {
                if (readFailureTimes <= 0) {
                    null
                } else {
                    readFailureTimes--
                    readFailure
                }
            },
            readGate = { position -> readGate?.invoke(position) },
            pingFailure = { timeoutMillis ->
                pings++
                lastPingTimeout = timeoutMillis
                pingFailure
            },
        )
    }
}

private class FakeSmbConnection(
    private val share: String,
    private val childrenByPath: Map<String, List<SmbEntry>>,
    private val bytesByPath: Map<String, ByteArray>,
    private val handles: MutableMap<String, RecordingSmbHandle>,
    private val allHandles: MutableList<RecordingSmbHandle>,
    private val openClose: MutableList<String>,
    private val closeOrder: MutableList<String>,
    private val closedShares: MutableList<String>,
    private val maxReadSize: Int,
    private val openReadFailure: () -> MediaSourceException?,
    private val listFailure: () -> Throwable?,
    private val readFailure: () -> Throwable?,
    private val readGate: (Long) -> Unit,
    private val pingFailure: (Long) -> Throwable?,
) : SmbConnection {

    override suspend fun list(path: String): List<SmbEntry> {
        listFailure()?.let { throw it }
        return childrenByPath["$share/$path"].orEmpty()
    }

    override suspend fun openRead(path: String): SmbReadHandle {
        openReadFailure()?.let { throw it }
        val bytes = bytesByPath["$share/$path"] ?: throw MediaSourceException(MediaFailure.NotFound, "没有这个文件：$path")
        openClose += "open"
        return RecordingSmbHandle(
            bytes,
            maxReadSize = maxReadSize,
            onClose = {
                openClose += "close"
                closeOrder += "handle"
            },
            readFailure = readFailure,
            readGate = readGate,
        ).also {
            handles["$share/$path"] = it
            allHandles += it
        }
    }

    override suspend fun ping(timeoutMillis: Long) {
        pingFailure(timeoutMillis)?.let { throw it }
    }

    override fun close() {
        closeOrder += "connection"
        closedShares += share
    }
}

/** 记录是否被关闭的读句柄。 */
class RecordingSmbHandle(
    private val bytes: ByteArray,
    override val maxReadSize: Int = 0,
    private val onClose: () -> Unit = {},
    private val readFailure: () -> Throwable? = { null },
    private val readGate: (Long) -> Unit = {},
) : SmbReadHandle, Closeable {

    var closed = false

    /** 被关掉的次数：同一句柄只该关一次。 */
    var closeCount = 0
        private set

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        readGate(position)
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
