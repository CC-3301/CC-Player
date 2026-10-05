package com.cc3301.ccplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 取图实现：视频取首帧附近的一帧，音频取文件内嵌封面（mp3 ID3 APIC / flac PICTURE / m4a covr 等）。
 * 目录与网络条目由 [CachedMediaPreviewSource] 拦掉，不会走到这里。
 */
class AndroidMediaPreviewExtractor(private val context: Context) : MediaPreviewExtractor {

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

    private fun <T> withRetriever(file: MediaFile, block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(file.location))
            block(retriever)
        } catch (unreadable: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private companion object {
        /** 抽帧位置取首帧附近：起始处常是黑场。 */
        const val FRAME_POSITION_MICROS = 1_000_000L
        const val FRAME_QUALITY = 85
    }
}
