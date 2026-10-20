package com.cc3301.ccplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cc3301.ccplayer.streaming.ByteSource
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 取图实现：视频取首帧附近的一帧，音频取文件内嵌封面（mp3 ID3 APIC / flac PICTURE / m4a covr 等）。
 *
 * 本地条目交给系统按 Uri 读；网络条目经 [networkReads] 打开随机读句柄，
 * 由 [MediaMetadataRetriever] 按需读取字节范围，不整文件下载。目录条目不经过这里。
 */
class AndroidMediaPreviewExtractor(
    private val context: Context,
    private val networkReads: NetworkMediaReads? = null,
) : MediaPreviewExtractor {

    override suspend fun imageBytes(file: MediaFile): ByteArray? = withContext(Dispatchers.IO) {
        withRetriever(file) { retriever ->
            when (mediaKindOf(file.name)) {
                MediaKind.Video -> frameBytes(retriever)
                MediaKind.Audio -> retriever.embeddedPicture
                null -> null
            }
        }
    }

    override suspend fun durationMillis(file: MediaFile): Long? = withContext(Dispatchers.IO) {
        withRetriever(file) { retriever ->
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }

    private fun frameBytes(retriever: MediaMetadataRetriever): ByteArray? {
        val frame = retriever.getFrameAtTime(FRAME_POSITION_MICROS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        return ByteArrayOutputStream().use { output ->
            frame.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, output)
            output.toByteArray()
        }
    }

    private suspend fun <T> withRetriever(file: MediaFile, block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        var networkSource: ByteSource? = null
        return try {
            if (file.source == MediaSource.Network) {
                val source = networkReads?.openRead(file.location) ?: return null
                networkSource = source
                retriever.setDataSource(RangeMediaDataSource(source))
            } else {
                retriever.setDataSource(context, Uri.parse(file.location))
            }
            block(retriever)
        } catch (unreadable: Exception) {
            null
        } finally {
            networkSource?.let { source -> runCatching { source.close() } }
            retriever.release()
        }
    }

    private companion object {
        /** 抽帧位置取首帧附近：起始处常是黑场。 */
        const val FRAME_POSITION_MICROS = 1_000_000L
        const val FRAME_QUALITY = 85
    }
}

/** 把随机读句柄接到系统解码器的数据源接口上；解码器按需读范围，不整文件下载。 */
private class RangeMediaDataSource(private val source: ByteSource) : MediaDataSource() {

    override fun getSize(): Long = source.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= source.size) return -1
        val read = source.readAt(position, buffer, offset, size)
        return if (read == 0) -1 else read
    }

    override fun close() {
        source.close()
    }
}
