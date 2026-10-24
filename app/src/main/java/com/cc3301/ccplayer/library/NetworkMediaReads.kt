package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource

/**
 * 网络媒体随机读接缝：预览抽取按字节范围读取文件，不整文件下载。
 *
 * 网络源实现按位置返回随机读句柄，抽取结束时由调用方关闭。
 */
fun interface NetworkMediaReads {

    /** 打开 [location] 的随机读句柄；调用方负责关闭。位置不属于任何网络源时抛出来源失败异常。 */
    suspend fun openRead(location: String): ByteSource

    /**
     * 位置所属的连接源标识：同一书签下的条目共用并发配额。
     * 默认把位置自身当作一个源；各协议实现按书签返回。
     */
    suspend fun sourceKey(location: String): String = location
}
