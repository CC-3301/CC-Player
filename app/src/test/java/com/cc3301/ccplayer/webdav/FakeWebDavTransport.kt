package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException

/** 假传输层：用「地址 → 子项」的映射代替真 WebDAV 服务器。 */
class FakeWebDavTransport : WebDavTransport {

    var failure: MediaSourceException? = null

    /** 置真时读句柄在读取时抛区间不支持，模拟忽略 Range 的服务器。 */
    var ignoreRange: Boolean = false

    /** 每次调用用到的用户名与密码。 */
    val logins = mutableListOf<Pair<String, String?>>()

    /** 每次列目录用到的地址。 */
    val listings = mutableListOf<String>()

    /** 每次打开读句柄时给出的文件大小。 */
    val openedSizes = mutableListOf<Long>()

    private val childrenByUrl = mutableMapOf<String, List<WebDavEntry>>()
    private val bytesByUrl = mutableMapOf<String, ByteArray>()

    /** 已打开的读句柄，按地址。 */
    val handles = mutableMapOf<String, RecordingWebDavHandle>()

    /** 依次打开过的全部读句柄，用来断言关闭次数。 */
    val allHandles = mutableListOf<RecordingWebDavHandle>()

    fun addDirectory(url: String, vararg children: WebDavEntry): FakeWebDavTransport {
        childrenByUrl[url] = children.toList()
        return this
    }

    fun addFile(url: String, bytes: ByteArray): FakeWebDavTransport {
        bytesByUrl[url] = bytes
        return this
    }

    override suspend fun list(url: String, username: String, password: String?): List<WebDavEntry> {
        failure?.let { throw it }
        logins += username to password
        listings += url
        return childrenByUrl[url].orEmpty()
    }

    /** 打开读句柄前执行；测试用它把打开卡在中间。 */
    var openGate: (() -> Unit)? = null

    override suspend fun openRead(url: String, username: String, password: String?, size: Long): WebDavReadHandle {
        failure?.let { throw it }
        openGate?.invoke()
        logins += username to password
        openedSizes += size
        val bytes = bytesByUrl[url] ?: throw MediaSourceException(MediaFailure.NotFound, "没有这个文件：$url")
        return RecordingWebDavHandle(bytes, ignoreRange).also {
            handles[url] = it
            allHandles += it
        }
    }
}

/** 记录是否被关闭的读句柄；[ignoreRange] 置真时读取抛区间不支持。 */
class RecordingWebDavHandle(
    private val bytes: ByteArray,
    private val ignoreRange: Boolean = false,
) : WebDavReadHandle {

    var closed = false

    /** 被关掉的次数：同一个句柄只该关一次。 */
    var closeCount = 0
        private set

    /** 关闭发生在哪条线程上：关闭只该在关源线程执行。 */
    var closedOn: String? = null

    override val size: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (ignoreRange) throw WebDavRangeUnsupportedException("服务器忽略了区间请求")
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun close() {
        closeCount++
        closed = true
        closedOn = Thread.currentThread().name
    }
}

fun webDavDirectory(name: String, lastModified: Long = 0): WebDavEntry =
    WebDavEntry(name = name, isDirectory = true, size = 0, lastModified = lastModified)

fun webDavFile(name: String, size: Long = 1024, lastModified: Long = 1_700_000_000_000): WebDavEntry =
    WebDavEntry(name = name, isDirectory = false, size = size, lastModified = lastModified)
