package com.cc3301.ccplayer.smb

import com.cc3301.ccplayer.streaming.ByteSource

/** 读源持有的一次会话占用：关句柄、交还会话引用。 */
interface SmbReadLease {

    /** 当前打开的文件句柄。 */
    val handle: SmbReadHandle

    /** 关闭句柄并交还会话引用；同一条租约只关一次。 */
    fun close()

    /** 作废会话后关闭句柄并交还引用。 */
    fun discard()
}

/**
 * 会话层打开的随机读句柄：传输类失败只作废本会话，把原失败抛给调用方。
 *
 * 停滞类失败与传输类同一形态：这一层不重连。重连要过存活确认与建连预算，耗时超过回环层的
 * 3 秒停滞判据；重连上来的句柄在回环层眼里已经陈旧，等于上下两层各重试一次。
 * 重建源与重取该段由回环层下一次请求完成。
 * 失败处理只动这次读用的那条租约，别人正在用的健康句柄不受影响。
 */
class SmbStreamSource(
    private val lease: SmbReadLease,
) : ByteSource {

    override val size: Long get() = lease.handle.size

    override val maxReadBytes: Int get() = lease.handle.maxReadSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = try {
        lease.handle.readAt(position, buffer, offset, length)
    } catch (failure: Throwable) {
        // 业务失败由调用方按原因报错，不动会话。
        if (!smbConnectionLost(failure)) throw failure
        lease.discard()
        throw failure
    }

    override fun close() = lease.close()

    /** 作废：会话一并丢弃，下一次取用必须重新建连。 */
    override fun discard() = lease.discard()
}
