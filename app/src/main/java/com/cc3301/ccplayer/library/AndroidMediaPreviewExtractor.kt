package com.cc3301.ccplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cc3301.ccplayer.streaming.ByteSource
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 取图实现：视频取首帧附近的一帧，音频取文件内嵌封面（mp3 ID3 APIC / flac PICTURE / m4a covr 等）。
 *
 * 本地条目交给系统按 Uri 读；网络条目经随机读句柄交给解码器，按需读取字节范围，不整文件下载。
 * 目录条目不经过这里。
 */
class AndroidMediaPreviewExtractor(
    private val context: Context,
    networkReads: NetworkMediaReads? = null,
) : MediaPreviewExtractor {

    private val networkPreview = networkReads?.let { reads ->
        NetworkPreviewExtractor(reads, AndroidNetworkPreviewDecoder())
    }

    override suspend fun imageBytes(file: MediaFile): ByteArray? = withContext(Dispatchers.IO) {
        if (file.source == MediaSource.Network) {
            return@withContext networkPreview?.imageBytes(file)
        }
        withRetriever(file) { retriever ->
            when (mediaKindOf(file.name)) {
                MediaKind.Video -> frameBytes(retriever)
                MediaKind.Audio -> retriever.embeddedPicture
                null -> null
            }
        }
    }

    override suspend fun durationMillis(file: MediaFile): Long? {
        // 网络条目不显示时长角标（ADR-0017）。
        if (file.source != MediaSource.Local) return null
        return withContext(Dispatchers.IO) {
            withRetriever(file) { retriever ->
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }
    }

    private fun <T> withRetriever(file: MediaFile, block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(file.location))
            block(retriever)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}

/** 网络解码：把随机读句柄包成系统数据源，交给 MediaMetadataRetriever 取图。 */
private class AndroidNetworkPreviewDecoder : NetworkPreviewDecoder {

    override fun imageBytes(source: ByteSource, kind: MediaKind): ByteArray? {
        val retriever = MediaMetadataRetriever()
        val dataSource = RangeMediaDataSource(source)
        return try {
            retriever.setDataSource(dataSource)
            when (kind) {
                MediaKind.Video -> frameBytes(retriever)
                MediaKind.Audio -> retriever.embeddedPicture
            }
        } catch (unreadable: Exception) {
            null
        } finally {
            retriever.release()
            // 句柄由调用方在 release 之后关闭。
        }
    }
}

/** 把随机读句柄接到系统解码器的数据源接口上；句柄的关闭责任在调用方。 */
private class RangeMediaDataSource(private val source: ByteSource) : MediaDataSource() {

    override fun getSize(): Long = source.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        rangeReadAt(source, position, buffer, offset, size)

    override fun close() {
        source.close()
    }
}

/** 抽帧位置取首帧附近：起始处常是黑场。 */
private const val FRAME_POSITION_MICROS = 1_000_000L
private const val FRAME_QUALITY = 85

private fun frameBytes(retriever: MediaMetadataRetriever): ByteArray? {
    val frame = retriever.getFrameAtTime(FRAME_POSITION_MICROS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: return null
    return ByteArrayOutputStream().use { output ->
        frame.compress(Bitmap.CompressFormat.JPEG, FRAME_QUALITY, output)
        output.toByteArray()
    }
}
