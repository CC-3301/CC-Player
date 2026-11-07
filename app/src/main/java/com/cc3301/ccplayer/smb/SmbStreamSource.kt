package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.streaming.ByteSource

/** 读源持有的一次会话占用：关句柄、交还会话引用、换一条会话重新打开。 */
interface SmbReadLease {

    /** 当前打开的文件句柄。 */
    val handle: SmbReadHandle

    /** 关闭句柄并交还会话引用。 */
    fun close()

    /** 作废会话后关闭句柄并交还引用。 */
    fun discard()

    /** 作废当前会话、关掉当前句柄，换一条会话重新打开同一文件；建连与关闭都在调用线程上做完。 */
    fun replace(): SmbReadLease
}

/**
 * 会话层打开的随机读句柄：传输类失败作废会话、重连并重发一次；停滞类失败只作废不重建。
 *
 * 关闭只置标记并关当前句柄，立即返回，不等重连；重连回来发现已关闭会自己收掉新句柄。
 * 换句柄时先关旧句柄再开新，同一文件任何时刻只有一个读句柄。
 */
class SmbStreamSource(
    private var current: SmbReadLease,
) : ByteSource {

    @Volatile
    private var handle: SmbReadHandle = current.handle

    @Volatile
    private var closed = false

    private val stateLock = Any()

    /** 已有人在重连：同时只允许一次，其余请求按原失败返回。 */
    private var reopening = false

    override val size: Long get() = handle.size

    override val maxReadBytes: Int get() = handle.maxReadSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val opened = handle
        try {
            return opened.readAt(position, buffer, offset, length)
        } catch (failure: Throwable) {
            if (!smbConnectionLost(failure)) throw failure
            if (smbStalled(failure)) {
                // 停滞类交给回环层的陈旧标记与重发：这里重连必然超过它的 3 秒判据。
                current.discard()
                throw failure
            }
            val reopened = reopen() ?: throw failure
            return reopened.readAt(position, buffer, offset, length)
        }
    }

    /** 换一条会话重新打开；已关闭或别的线程正在重连时返回 null，由调用方抛原失败。 */
    private fun reopen(): SmbReadHandle? {
        synchronized(stateLock) {
            if (closed || reopening) return null
            reopening = true
        }
        val replacement = try {
            current.replace()
        } catch (failure: Throwable) {
            synchronized(stateLock) { reopening = false }
            throw failure
        }
        val stale: SmbReadLease
        synchronized(stateLock) {
            reopening = false
            if (closed) {
                replacement.close()
                return null
            }
            stale = current
            current = replacement
            handle = replacement.handle
        }
        stale.close()
        return replacement.handle
    }

    /** 关闭：只置标记、关当前句柄，立即返回；等重连的那一次回来会自己收掉新句柄。 */
    override fun close() {
        val ending: SmbReadLease
        synchronized(stateLock) {
            if (closed) return
            closed = true
            ending = current
        }
        ending.close()
    }

    /** 作废：会话一并丢弃，下一次取用必须重新建连。 */
    override fun discard() {
        val ending: SmbReadLease
        synchronized(stateLock) {
            if (closed) return
            closed = true
            ending = current
        }
        ending.discard()
    }
}
