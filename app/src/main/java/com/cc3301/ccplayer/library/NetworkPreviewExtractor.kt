package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络预览解码接缝：真实现用 MediaMetadataRetriever 读取字节范围，测试用假实现。
 *
 * 实现不得关闭 [source]；句柄统一由 [NetworkPreviewExtractor] 关闭。
 */
fun interface NetworkPreviewDecoder {

    /** 从 [source] 取图；没有可用图时返回 null。 */
    fun imageBytes(source: ByteSource, kind: MediaKind, targetWidth: Int): ByteArray?
}

/**
 * 网络预览取图：打开随机读句柄后交给 [decoder]，只在需要时读字节范围。
 *
 * 句柄无论解码成功与否都会关闭；打开或解码失败抛出异常，由上层保持占位图并退避重试。
 */
class NetworkPreviewExtractor(
    private val reads: NetworkMediaReads,
    private val decoder: NetworkPreviewDecoder,
) {

    suspend fun imageBytes(file: MediaFile, targetWidth: Int = THUMBNAIL_TARGET_WIDTH): ByteArray? =
        withContext(Dispatchers.IO) {
            val kind = mediaKindOf(file.name) ?: return@withContext null
            val source = reads.openRead(file.location)
            try {
                decoder.imageBytes(source, kind, targetWidth)
            } finally {
                runCatching { source.close() }
            }
        }
}

/**
 * 把随机读的一次读取映射成 MediaDataSource.readAt 的返回值：
 * 位置越界或读到末尾返回 -1，短读按实际字节数返回。
 */
internal fun rangeReadAt(source: ByteSource, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
    if (position < 0 || position >= source.size) return -1
    val read = source.readAt(position, buffer, offset, size)
    return if (read <= 0) -1 else read
}
