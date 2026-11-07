package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.streaming.ByteSource

/**
 * 会话层打开的随机读句柄：读取遇传输类失败时作废会话、重连并重发一次。
 *
 * 关闭只关文件句柄并交还会话占用，不关会话；作废与重连由 [reopen] 完成。
 */
class SmbStreamSource(
    private var handle: SmbReadHandle,
    private val onClose: () -> Unit,
    private val reopen: () -> SmbReadHandle,
) : ByteSource {

    private val lock = Any()

    private var closed = false

    override val size: Long get() = handle.size

    override val maxReadBytes: Int get() = handle.maxReadSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val current = handle
        try {
            return current.readAt(position, buffer, offset, length)
        } catch (failure: Throwable) {
            if (!smbConnectionLost(failure)) throw failure
            val reopened = reopenHandle() ?: throw failure
            return reopened.readAt(position, buffer, offset, length)
        }
    }

    /** 换一条会话重新打开；已关闭时返回 null，由调用方抛原失败。 */
    private fun reopenHandle(): SmbReadHandle? = synchronized(lock) {
        if (closed) return null
        val previous = handle
        val next = reopen()
        handle = next
        runCatching { previous.close() }
        next
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { handle.close() }
        }
        onClose()
    }
}
