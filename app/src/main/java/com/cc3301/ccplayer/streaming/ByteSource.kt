package com.cc3301.ccplayer.streaming

import java.io.Closeable

/** 可按位置随机读的字节源，长度在登记时已知。 */
interface ByteSource : Closeable {

    val size: Long

    /** 单次底层读取的尺寸上限；0 表示由调用方决定。 */
    val maxReadBytes: Int get() = 0

    /** 从 [position] 起读最多 [length] 字节到 [buffer] 的 [offset]；读到末尾返回 0。 */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * 作废本次读取：关闭句柄，并让底层丢弃可能已失效的会话。
     *
     * 预览读取超时、取消或打开失败时用它代替 [close]；默认只关闭，可重复调用。
     * 下一次读取必须重新建连，不能复用被作废的会话。
     */
    fun discard() = close()
}
