package com.cc3301.ccplayer.webdav

import com.cc3301.ccplayer.streaming.ByteSource

/** 把 WebDAV 随机读句柄接到回环代理的字节源上。 */
class WebDavStreamSource(private val handle: WebDavReadHandle) : ByteSource {

    override val size: Long get() = handle.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        handle.readAt(position, buffer, offset, length)

    override fun close() {
        runCatching { handle.close() }
    }
}
