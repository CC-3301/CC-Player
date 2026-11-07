package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.streaming.ByteSource

/** 读源持有的一次会话占用：关句柄、交还会话引用、换一条会话重新打开。 */
interface SmbReadLease {

    /** 当前打开的文件句柄。 */
    val handle: SmbReadHandle

    /** 关闭句柄并交还会话引用；同一条租约只关一次。 */
    fun close()

    /** 作废会话后关闭句柄并交还引用。 */
    fun discard()

    /** 关掉旧句柄、作废旧会话，换一条会话重新打开同一文件；建连与关闭都在调用线程上做完。 */
    fun replace(): SmbReadLease
}

/**
 * 会话层打开的随机读句柄：传输类失败作废会话、重连并重发一次；停滞类失败只作废不重建。
 *
 * 关闭只置标记并关当前句柄，立即返回，不等重连；重连回来发现已关闭会自己收掉新句柄。
 * 换句柄时先关旧句柄再开新，同一文件任何时刻只有一个读句柄。
 * 失败处理只动这次读用的那条租约，别人刚换好的健康句柄不受影响。
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
        val opened = current
        try {
            return opened.handle.readAt(position, buffer, offset, length)
        } catch (failure: Throwable) {
            if (!smbConnectionLost(failure)) throw failure
            if (smbStalled(failure)) {
                // 停滞类由回环层打陈旧标记后重取该段：这里重连必然超过它的 3 秒判据。
                opened.discard()
                throw failure
            }
            val reopened = reopen(opened) ?: throw failure
            return reopened.readAt(position, buffer, offset, length)
        }
    }

    /**
     * 换一条会话重新打开：只动这次读用的那条租约；别人已经换好了就直接用新的。
     * 已关闭或别的线程正在重连时返回 null，由调用方抛原失败。
     */
    private fun reopen(failed: SmbReadLease): SmbReadHandle? {
        synchronized(stateLock) {
            if (closed) return null
            if (current !== failed) return current.handle
            if (reopening) return null
            reopening = true
        }
        val replacement = try {
            failed.replace()
        } catch (failure: Throwable) {
            synchronized(stateLock) { reopening = false }
            throw failure
        }
        var abandoned: SmbReadLease? = null
        var result: SmbReadHandle? = null
        synchronized(stateLock) {
            reopening = false
            if (closed || current !== failed) {
                // 期间被关闭或被别人换掉：这次新建的作废，锁外关。
                abandoned = replacement
            } else {
                current = replacement
                handle = replacement.handle
                result = replacement.handle
            }
        }
        abandoned?.close()
        return result
    }

    /** 关闭：只置标记、取走当前租约，关句柄在锁外；等重连的那一次回来会自己收掉新句柄。 */
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
