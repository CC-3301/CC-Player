package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.streaming.ByteSource

/** 把 SMB 随机读句柄接到回环代理的字节源上。 */
class SmbStreamSource(private val connection: SmbConnection, private val handle: SmbReadHandle) : ByteSource {

    override val size: Long get() = handle.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        handle.readAt(position, buffer, offset, length)

    /** 先关连接再关句柄：连接关闭会让阻塞中的读尽快退出，句柄关闭只是尽力收尾。 */
    override fun close() {
        runCatching { connection.close() }
        runCatching { handle.close() }
    }
}
