package com.cc3301.ccplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.cc3301.ccplayer.streaming.ByteSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 取图实现：视频按比例抽帧并跳过纯黑/纯色帧，音频取文件内嵌封面
 * （mp3 ID3 APIC / flac PICTURE / m4a covr 等）。
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

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (file.source == MediaSource.Network) {
            return@withContext networkPreview?.imageBytes(file, targetWidth)
        }
        withRetriever(file) { retriever ->
            when (mediaKindOf(file.name)) {
                MediaKind.Video -> videoFrameBytes(retriever, LOCAL_FRAME_COUNT, targetWidth)
                MediaKind.Audio -> coverBytes(retriever, targetWidth)
                null -> null
            }
        }
    }

    override suspend fun durationMillis(file: MediaFile): Long? {
        // 网络条目不显示时长角标（ADR-0017）。
        if (file.source != MediaSource.Local) return null
        // 读取异常向上抛：调用方据此区分“确定没有时长”与“读取失败”。
        return withContext(Dispatchers.IO) {
            withRetriever(file) { retriever -> durationMillis(retriever) }
        }
    }

    /** 本地文件一次打开读取器，同时取时长与预览图；时长读取失败不影响已解出的图片。 */
    override suspend fun imageAndDuration(file: MediaFile, targetWidth: Int): MediaPreviewExtraction =
        withContext(Dispatchers.IO) {
            if (file.source == MediaSource.Network) {
                return@withContext MediaPreviewExtraction(
                    imageBytes = networkPreview?.imageBytes(file, targetWidth),
                    duration = PreviewDuration.Unavailable,
                )
            }
            withRetriever(file) { retriever ->
                when (mediaKindOf(file.name)) {
                    MediaKind.Video -> MediaPreviewExtraction(
                        imageBytes = videoFrameBytes(retriever, LOCAL_FRAME_COUNT, targetWidth),
                        duration = retriever.readDuration(),
                    )

                    MediaKind.Audio -> MediaPreviewExtraction(
                        imageBytes = coverBytes(retriever, targetWidth),
                        duration = retriever.readDuration(),
                    )

                    null -> MediaPreviewExtraction(imageBytes = null, duration = retriever.readDuration())
                }
            }
        }

    private fun <T> withRetriever(file: MediaFile, block: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(file.location))
            block(retriever)
        } finally {
            retriever.release()
        }
    }
}

/** 网络解码：把随机读句柄包成系统数据源，交给 MediaMetadataRetriever 取图。 */
private class AndroidNetworkPreviewDecoder : NetworkPreviewDecoder {

    override fun imageBytes(source: ByteSource, kind: MediaKind, targetWidth: Int): ByteArray? {
        val retriever = MediaMetadataRetriever()
        val dataSource = RangeMediaDataSource(source)
        return try {
            retriever.setDataSource(dataSource)
            when (kind) {
                MediaKind.Video -> videoFrameBytes(retriever, NETWORK_FRAME_COUNT, targetWidth)
                MediaKind.Audio -> coverBytes(retriever, targetWidth)
            }
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

/** 按时长比例抽帧；跳过纯色帧，全部不合格时用约三分之一处的帧兜底。 */
private fun videoFrameBytes(retriever: MediaMetadataRetriever, frameCount: Int, targetWidth: Int): ByteArray? {
    // 时长读不到时改用固定候选位置，不因此丢弃已经能解出的帧。
    val duration = try {
        durationMillis(retriever)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unreadable: Exception) {
        null
    } ?: 0
    val decoded = decodedFrames(framePositions(duration, frameCount)) { position ->
        retriever.getFrameAtTime(position.micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?.let { frame -> scaleFrame(frame, targetWidth) }
    }
    // 候选帧位置存在却一帧都没解出，属于本次抽取失败，不能当成“确定没有图”。
    if (decoded.isEmpty()) throw IOException("没有解出可用的视频帧")
    val chosen = chooseFrame(decoded, ::isSolidFrame)
    decoded.forEach { (_, frame) ->
        if (frame !== chosen) frame.recycle()
    }
    val compressed = chosen?.let { frame ->
        compressFrame(frame).also { frame.recycle() }
    }
    return compressed ?: throw IOException("视频帧压缩失败")
}

/** 音频内嵌封面按与视频帧相同的宽度和 JPEG 质量处理；没有封面返回 null。 */
private fun coverBytes(retriever: MediaMetadataRetriever, targetWidth: Int): ByteArray? {
    val picture = retriever.embeddedPicture ?: return null
    val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
        ?: throw IOException("封面解码失败")
    return try {
        val scaled = scaleFrame(bitmap, targetWidth)
        try {
            compressFrame(scaled) ?: throw IOException("封面压缩失败")
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}

private fun MediaMetadataRetriever.readDuration(): PreviewDuration = try {
    when (val millis = durationMillis(this)) {
        null -> PreviewDuration.Unavailable
        else -> PreviewDuration.Known(millis)
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (unreadable: Exception) {
    PreviewDuration.Failed
}

private fun durationMillis(retriever: MediaMetadataRetriever): Long? =
    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

/** 按 320–512 的输出宽度缩放；宽度已相同时不重建位图。 */
private fun scaleFrame(frame: Bitmap, targetWidth: Int): Bitmap {
    val width = targetFrameWidth(frame.width, targetWidth)
    if (width <= 0 || width == frame.width) return frame
    val height = maxOf(1, (frame.height.toLong() * width / frame.width).toInt())
    return frame.scale(width, height, filter = true).also { frame.recycle() }
}

private fun compressFrame(frame: Bitmap): ByteArray? = ByteArrayOutputStream().use { output ->
    if (frame.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, output)) {
        output.toByteArray()
    } else {
        null
    }
}

/** 在缩略图上按网格采样亮度，亮度差太小视为纯色。 */
private fun isSolidFrame(frame: Bitmap): Boolean {
    if (frame.width <= 0 || frame.height <= 0) return true
    val columns = 16
    val rows = 16
    val luminances = IntArray(columns * rows) { index ->
        val column = index % columns
        val row = index / columns
        val x = column * (frame.width - 1) / (columns - 1)
        val y = row * (frame.height - 1) / (rows - 1)
        luminance(frame[x, y])
    }
    return isSolidLuminance(luminances)
}

private fun luminance(pixel: Int): Int {
    val red = (pixel shr 16) and 0xFF
    val green = (pixel shr 8) and 0xFF
    val blue = pixel and 0xFF
    return (red * 299 + green * 587 + blue * 114) / 1000
}
