package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaSourceException

/** 假传输层：用「地址 → 子项」的映射代替真 WebDAV 服务器。 */
class FakeWebDavTransport : WebDavTransport {

    var failure: MediaSourceException? = null

    /** 每次调用用到的用户名与密码。 */
    val logins = mutableListOf<Pair<String, String?>>()

    /** 每次列目录用到的地址。 */
    val listings = mutableListOf<String>()

    private val childrenByUrl = mutableMapOf<String, List<WebDavEntry>>()
    private val bytesByUrl = mutableMapOf<String, ByteArray>()

    /** 已打开的读句柄，按地址。 */
    val handles = mutableMapOf<String, RecordingWebDavHandle>()

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

    override suspend fun openRead(url: String, username: String, password: String?): WebDavReadHandle {
        failure?.let { throw it }
        logins += username to password
        val bytes = bytesByUrl[url] ?: throw MediaSourceException(MediaFailure.NotFound, "没有这个文件：$url")
        return RecordingWebDavHandle(bytes).also { handles[url] = it }
    }
}

/** 记录是否被关闭的读句柄。 */
class RecordingWebDavHandle(private val bytes: ByteArray) : WebDavReadHandle {

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
    }
}

fun webDavDirectory(name: String, lastModified: Long = 0): WebDavEntry =
    WebDavEntry(name = name, isDirectory = true, size = 0, lastModified = lastModified)

fun webDavFile(name: String, size: Long = 1024, lastModified: Long = 1_700_000_000_000): WebDavEntry =
    WebDavEntry(name = name, isDirectory = false, size = size, lastModified = lastModified)
