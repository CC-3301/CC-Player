package com.cc3301.ccplayer.smb

import java.io.Closeable

/** SMB 目录项。 */
data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** 已连接的 SMB 共享：列举、打开随机读句柄与存活确认。 */
interface SmbConnection : Closeable {

    /** 列出共享内目录的直接子项。 */
    suspend fun list(path: String): List<SmbEntry>

    /** 打开共享内文件的随机读句柄；调用方负责关闭。 */
    suspend fun openRead(path: String): SmbReadHandle

    /** 存活确认：一次 SMB2 ECHO 往返，[timeoutMillis] 内没有回应即抛错。 */
    suspend fun ping(timeoutMillis: Long)
}

/** 可按位置随机读的 SMB 文件句柄。 */
interface SmbReadHandle : Closeable {

    val size: Long

    /** 服务器协商的单次读取上限；0 表示未知。 */
    val maxReadSize: Int get() = 0

    /** 从 [position] 起读最多 [length] 字节到 [buffer] 的 [offset]；读到末尾返回 0。 */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** 建连接缝：真实现走 SMBJ，测试用假实现驱动会话层与文件树。 */
interface SmbConnector {

    /** 按给定参数连上共享；调用方负责关闭返回的连接。连接失败抛出来源失败异常。 */
    suspend fun connect(host: String, port: Int, share: String, username: String, password: String?): SmbConnection

    /** 列出服务器上的共享名；连不上或没有权限时抛出来源失败异常。 */
    suspend fun shares(host: String, port: Int, username: String, password: String?): List<String>
}
