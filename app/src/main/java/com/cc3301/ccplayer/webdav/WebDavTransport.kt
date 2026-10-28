package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.streaming.ByteSource

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

    /**
     * 打开 [url] 的随机读句柄；调用方负责关闭。失败时抛出来源失败异常。
     *
     * [size] 为目录列表给出的文件大小：给出正数时打开不发请求，并要求服务器支持区间读；
     * 未知大小时（播放读取）打开时探测一次长度。
     */
    suspend fun openRead(url: String, username: String, password: String?, size: Long): WebDavReadHandle
}

/** 可按位置随机读的 WebDAV 文件句柄，直接作为回环代理的字节源。 */
interface WebDavReadHandle : ByteSource
