package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource

/**
 * 网络媒体随机读接缝：预览抽取按字节范围读取文件，不整文件下载。
 *
 * 网络源实现按位置返回随机读句柄，抽取结束时由调用方关闭。
 */
fun interface NetworkMediaReads {

    /**
     * 打开 [location] 的随机读句柄；调用方负责关闭。
     * [size] 取自目录列表（0 表示未知），句柄长度直接用它，不再为取长度单独发请求。
     * 位置不属于任何网络源时抛出来源失败异常。
     */
    suspend fun openRead(location: String, size: Long): ByteSource
}
