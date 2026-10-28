package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException
import java.io.Closeable

/** 假连接器：用「共享 + 路径 → 子项」的映射代替真 SMB 服务器。 */
class FakeSmbConnector : SmbConnector {

    var failure: MediaSourceException? = null

    /** 每次连接用到的用户名与密码。 */
    val logins = mutableListOf<Pair<String, String?>>()

    /** 已建立连接时给的共享名。 */
    val shares = mutableListOf<String>()

    /** 服务器共享枚举返回的名称。 */
    var shareNames: List<String> = emptyList()

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
        return FakeSmbConnection(share, childrenByPath, bytesByPath, handles, closeOrder, closedShares)
    }
}

private class FakeSmbConnection(
    private val share: String,
    private val childrenByPath: Map<String, List<SmbEntry>>,
    private val bytesByPath: Map<String, ByteArray>,
    private val handles: MutableMap<String, RecordingSmbHandle>,
    private val closeOrder: MutableList<String>,
    private val closedShares: MutableList<String>,
) : SmbConnection {

    override suspend fun list(path: String): List<SmbEntry> = childrenByPath["$share/$path"].orEmpty()

    override suspend fun openRead(path: String): SmbReadHandle {
        val bytes = bytesByPath["$share/$path"] ?: throw MediaSourceException(MediaFailure.NotFound, "没有这个文件：$path")
        return RecordingSmbHandle(bytes) { closeOrder += "handle" }
            .also { handles["$share/$path"] = it }
    }

    override fun close() {
        closeOrder += "connection"
        closedShares += share
    }
}

/** 记录是否被关闭的读句柄。 */
class RecordingSmbHandle(
    private val bytes: ByteArray,
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
