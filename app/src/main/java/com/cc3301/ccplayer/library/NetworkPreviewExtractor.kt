package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.streaming.ByteSource
import com.cc3301.ccplayer.streaming.NetworkReadPriority
import com.cc3301.ccplayer.streaming.ReadKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * 网络预览取图：打开随机读句柄后用 [PreviewByteWindow] 包成本地字节，再交给 [decoder]。
 *
 * 句柄长度取自目录列表，打开时不发请求取长度；解码只读本地字节，缺失的块由窗口按上限预读补齐。
 * 句柄无论解码成功与否都会关闭；取消或超时时先关闭句柄，让阻塞中的传输读取退出。
 * 读取按 [readPriority] 让出给播放读取。打开或解码失败抛出异常，由上层保持占位图并退避重试。
 */
class NetworkPreviewExtractor(
    private val reads: NetworkMediaReads,
    private val readPriority: NetworkReadPriority = NetworkReadPriority.shared,
    private val decoder: NetworkPreviewDecoder,
) {

    suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val kind = mediaKindOf(file.name) ?: return@withContext null
            val source = readPriority.wrap(reads.openRead(file.location, file.size), ReadKind.Thumbnail)
            // 解码只读本地字节：当前块由解码线程补齐，后续块由预读线程补齐。
            val window = PreviewByteWindow(source)
            try {
                decode(window, kind, targetWidth)
            } finally {
                runCatching { window.close() }
            }
        }

    /**
     * 解码在独立协程里跑；父协程取消时关闭句柄，避免阻塞读把许可一直占住。
     * 解码侧的异常先收在子协程里：关闭句柄会让阻塞读抛错，取消收尾时按取消上报，不当成抽取失败。
     */
    private suspend fun decode(window: ByteSource, kind: MediaKind, targetWidth: Int): ByteArray? =
        coroutineScope {
            var image: ByteArray? = null
            var failure: Throwable? = null
            val decoding = async {
                try {
                    image = decoder.imageBytes(window, kind, targetWidth)
                } catch (error: Throwable) {
                    failure = error
                }
            }
            try {
                decoding.await()
            } catch (cancellation: CancellationException) {
                runCatching { window.close() }
                throw cancellation
            }
            failure?.let { error ->
                runCatching { window.close() }
                // 已经取消时结果是关闭句柄造成的，按取消收尾。
                currentCoroutineContext().ensureActive()
                throw error
            }
            image
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
