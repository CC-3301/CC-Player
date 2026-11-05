package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.connection.Connection
import java.io.Closeable
import java.io.IOException

/** 假连接器：用「共享 + 路径 → 子项」的映射代替真 SMB 服务器。 */
class FakeSmbConnector : SmbConnector {

    var failure: MediaSourceException? = null

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

    /** 客户端代数：每次作废加一；作废前建立的连接随之失效。 */
    override var generation: Long = 0L
        private set

    /** 作废次数，测试按次数断言。 */
    val resets: Int get() = generation.toInt()

    /** 建连过程中执行，用来模拟建连期间客户端被作废。 */
    var duringConnect: (() -> Unit)? = null

    /** 打开读句柄过程中挂起，用来模拟打开被超时或取消放弃。 */
    var duringOpenRead: (suspend () -> Unit)? = null

    private val childrenByPath = mutableMapOf<String, List<SmbEntry>>()
    private val bytesByPath = mutableMapOf<String, ByteArray>()

    /** 已打开的读句柄，按路径。 */
    val handles = mutableMapOf<String, RecordingSmbHandle>()

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
        failure?.let { throw it }
        logins += username to password
        shares += share
        duringConnect?.invoke()
        val createdGeneration = generation
        return FakeSmbConnection(
            share = share,
            childrenByPath = childrenByPath,
            bytesByPath = bytesByPath,
            handles = handles,
            closeOrder = closeOrder,
            closedShares = closedShares,
            maxReadSize = maxReadSize,
            openReadFailure = { openReadFailure },
            isStale = { generation != createdGeneration },
            duringOpenRead = { duringOpenRead?.invoke() },
        )
    }

    override fun reset() {
        generation++
    }
}

private class FakeSmbConnection(
    private val share: String,
    private val childrenByPath: Map<String, List<SmbEntry>>,
    private val bytesByPath: Map<String, ByteArray>,
    private val handles: MutableMap<String, RecordingSmbHandle>,
    private val closeOrder: MutableList<String>,
    private val closedShares: MutableList<String>,
    private val maxReadSize: Int,
    private val openReadFailure: () -> MediaSourceException?,
    private val isStale: () -> Boolean,
    private val duringOpenRead: suspend () -> Unit,
) : SmbConnection {

    override suspend fun list(path: String): List<SmbEntry> {
        ensureUsable()
        return childrenByPath["$share/$path"].orEmpty()
    }

    override suspend fun openRead(path: String): SmbReadHandle {
        ensureUsable()
        duringOpenRead()
        openReadFailure()?.let { throw it }
        val bytes = bytesByPath["$share/$path"] ?: throw MediaSourceException(MediaFailure.NotFound, "没有这个文件：$path")
        return RecordingSmbHandle(bytes, maxReadSize = maxReadSize) { closeOrder += "handle" }
            .also { handles["$share/$path"] = it }
    }

    override fun close() {
        closeOrder += "connection"
        closedShares += share
    }

    /** 旧客户端上的连接在作废后不可再用，与真实现一致。 */
    private fun ensureUsable() {
        if (isStale()) throw MediaSourceException(MediaFailure.Unreachable, "旧客户端的连接已作废")
    }
}

/** 记录是否被关闭与建连次数的 SMB 客户端；建连直接失败，不连服务器。 */
class RecordingSmbClient(config: SmbConfig) : SMBClient(config) {

    var closed = false

    /** 被要求建连的主机名。 */
    val connects = mutableListOf<String>()

    override fun connect(host: String, port: Int): Connection {
        connects += host
        throw IOException("测试用客户端不可用")
    }

    override fun close() {
        closed = true
        super.close()
    }
}

/** 记录是否被关闭的读句柄。 */
class RecordingSmbHandle(
    private val bytes: ByteArray,
    override val maxReadSize: Int = 0,
    private val onClose: () -> Unit = {},
) : SmbReadHandle, Closeable {

    var closed = false

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closed = true
        onClose()
    }
}

fun directoryEntry(name: String, size: Long = 0, lastModified: Long = 0): SmbEntry =
    SmbEntry(name = name, isDirectory = true, size = size, lastModified = lastModified)

fun fileEntry(name: String, size: Long = 1024, lastModified: Long = 1_700_000_000_000): SmbEntry =
    SmbEntry(name = name, isDirectory = false, size = size, lastModified = lastModified)
