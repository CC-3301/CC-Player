package com.cc3301.ccplayer.webdav

import java.io.Closeable

/** WebDAV 目录项。 */
data class WebDavEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    /** 修改时间，Unix 毫秒；服务器没给时为 0。 */
    val lastModified: Long,
)

/**
 * WebDAV 访问接缝：真实现走 OkHttp，测试用假实现驱动文件树与流代理。
 *
 * HTTP 无会话，凭据随每次调用给出。
 */
interface WebDavTransport {

    /** 用 PROPFIND 列出 [url] 的直接子项；连接失败或状态码非 2xx 时抛出来源失败异常。 */
    suspend fun list(url: String, username: String, password: String?): List<WebDavEntry>

    /** 打开 [url] 的随机读句柄；调用方负责关闭。失败时抛出来源失败异常。 */
    suspend fun openRead(url: String, username: String, password: String?): WebDavReadHandle
}

/** 可按位置随机读的 WebDAV 文件句柄。 */
interface WebDavReadHandle : Closeable {

    val size: Long

    /** 从 [position] 起读最多 [length] 字节到 [buffer] 的 [offset]；读到末尾返回 0。 */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}
