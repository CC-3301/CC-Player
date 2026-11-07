package com.cc3301.ccplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.streaming.ByteSource
import java.io.ByteArrayOutputStream
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
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) : MediaPreviewExtractor {

    // 预览源在进程内只有一个：抽帧器随它创建一次并复用。context 是 application context，不持有界面。
    private val media3 = Media3VideoFrameExtractor(context, diagnostics)

    private val networkPreview = networkReads?.let { reads ->
        NetworkPreviewExtractor(
            reads,
            decoder = AndroidNetworkPreviewDecoder(media3, diagnostics),
            diagnostics = diagnostics,
        )
    }

    override suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? = withContext(Dispatchers.IO) {
        withRetriever(file) { retriever ->
            when (mediaKindOf(file.name)) {
                MediaKind.Video -> localVideoBytes(media3, file, retriever, targetWidth)
                MediaKind.Audio -> audioCoverBytes(retriever, targetWidth, file)
                null -> null
            }
        }
    }

    override suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation? =
        prepareNetworkPreview(file, PREVIEW_PREFETCH_RANK)

    override suspend fun prepareNetworkPreview(
        file: MediaFile,
        readAheadRank: Long,
    ): NetworkPreviewPreparation? =
        if (file.source == MediaSource.Network) networkPreview?.prepare(file, readAheadRank) else null

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
            withRetriever(file) { retriever ->
                when (mediaKindOf(file.name)) {
                    MediaKind.Video -> {
                        val duration = retriever.readDuration()
                        MediaPreviewExtraction(
                            imageBytes = localVideoBytes(media3, file, retriever, duration, targetWidth),
                            duration = duration,
                        )
                    }

                    MediaKind.Audio -> MediaPreviewExtraction(
                        imageBytes = audioCoverBytes(retriever, targetWidth, file),
                        duration = retriever.readDuration(),
                    )

                    null -> MediaPreviewExtraction(imageBytes = null, duration = retriever.readDuration())
                }
            }
        }

    private suspend fun <T> withRetriever(file: MediaFile, block: suspend (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        return try {
            try {
                retriever.setDataSource(context, file.location.toUri())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (invalid: IllegalArgumentException) {
                // 位置解析不了交给上层按「路径解析」记，不改成打开数据源。
                throw invalid
            } catch (denied: SecurityException) {
                throw denied
            } catch (failure: Exception) {
                throw PreviewStageException(PreviewFailureStage.OpenSource, "打开数据源失败", failure)
            }
            block(retriever)
        } finally {
            retriever.release()
        }
    }

    /** 本地音频封面：先取系统内嵌图；取不到或解码失败时用 ID3v2 APIC 再取一次。 */
    private fun audioCoverBytes(
        retriever: MediaMetadataRetriever,
        targetWidth: Int,
        file: MediaFile,
    ): ByteArray? {
        val systemPicture = embeddedPictureOrNull(retriever)
        var systemDecodeFailure: PreviewStageException? = null
        if (systemPicture != null) {
            try {
                return encodeCover(systemPicture, targetWidth)
            } catch (decode: PreviewStageException) {
                // 系统给了图却解不出来：继续走 ID3 兜底；两条都失败时保留这次解码失败作为阶段。
                systemDecodeFailure = decode
            }
        }
        val id3 = readLocalId3Cover(context, file)
        diagnostics.log {
            val systemLabel = if (systemDecodeFailure != null) "解码失败" else "无"
            "封面兜底: ${file.name} 系统取图${systemLabel}，ID3 解析 ${id3Label(id3)}"
        }
        return when (id3) {
            is Id3CoverResult.Picture -> encodeCover(id3.bytes, targetWidth)
            is Id3CoverResult.Missing -> {
                val decode = systemDecodeFailure
                if (decode != null) throw decode
                null
            }

            is Id3CoverResult.Failed -> {
                val decode = systemDecodeFailure
                throw if (decode != null) decode else PreviewStageException(PreviewFailureStage.Read, "封面解析失败")
            }
        }
    }
}

/** 网络解码：视频走 Media3，音频仍用系统内嵌封面与 ID3 兜底。 */
private class AndroidNetworkPreviewDecoder(
    private val media3: Media3VideoFrameExtractor,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) : NetworkPreviewDecoder {

    override suspend fun imageBytes(source: ByteSource, file: MediaFile, targetWidth: Int): ByteArray? =
        when (mediaKindOf(file.name)) {
            MediaKind.Video -> networkVideoFrameBytes(media3, source, file, targetWidth)
            MediaKind.Audio -> networkAudioCoverBytes(source, file, targetWidth)
            null -> null
        }

    /** 网络音频封面：先取系统内嵌图；取不到或解码失败时按字节范围只读 ID3 标签段再取一次。 */
    private fun networkAudioCoverBytes(source: ByteSource, file: MediaFile, targetWidth: Int): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            try {
                retriever.setDataSource(NonClosingMediaDataSource(source))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw PreviewStageException(PreviewFailureStage.OpenSource, "打开数据源失败", failure)
            }
            audioCoverBytes(retriever, source, targetWidth)
        } finally {
            retriever.release()
            // 句柄由调用方在读取会话收尾时关闭。
        }
    }

    /** 网络音频封面：先取系统内嵌图；取不到或解码失败时按字节范围只读 ID3 标签段再取一次。 */
    private fun audioCoverBytes(
        retriever: MediaMetadataRetriever,
        source: ByteSource,
        targetWidth: Int,
    ): ByteArray? {
        val systemPicture = embeddedPictureOrNull(retriever)
        var systemDecodeFailure: PreviewStageException? = null
        if (systemPicture != null) {
            try {
                return encodeCover(systemPicture, targetWidth)
            } catch (decode: PreviewStageException) {
                systemDecodeFailure = decode
            }
        }
        val id3 = readId3Cover(ByteSourceCoverSource(source))
        diagnostics.log {
            val systemLabel = if (systemDecodeFailure != null) "解码失败" else "无"
            "网络封面: 系统取图${systemLabel}，ID3 解析 ${id3Label(id3)}"
        }
        return when (id3) {
            is Id3CoverResult.Picture -> encodeCover(id3.bytes, targetWidth)
            is Id3CoverResult.Missing -> {
                val decode = systemDecodeFailure
                if (decode != null) throw decode
                null
            }

            is Id3CoverResult.Failed -> {
                val decode = systemDecodeFailure
                throw if (decode != null) decode else PreviewStageException(PreviewFailureStage.Read, "封面解析失败")
            }
        }
    }
}

/** 把随机读句柄接到系统解码器的数据源接口上；关闭不关闭句柄，句柄责任在调用方。 */
private class NonClosingMediaDataSource(private val source: ByteSource) : MediaDataSource() {

    override fun getSize(): Long = source.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        rangeReadAt(source, position, buffer, offset, size)

    override fun close() = Unit
}

/** 未标记阶段的抽帧异常按「解码」收尾；已带 [PreviewStageException] 的异常原样透传，取消原样抛出。 */
internal suspend fun <T> decodeStage(message: String, block: suspend () -> T): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: PreviewStageException) {
    throw failure
} catch (failure: Exception) {
    throw PreviewStageException(PreviewFailureStage.Decode, message, failure)
}

/** 本地视频：时长只用于挑候选位置，抽帧交给 Media3。 */
private suspend fun localVideoBytes(
    media3: Media3VideoFrameExtractor,
    file: MediaFile,
    retriever: MediaMetadataRetriever,
    targetWidth: Int,
): ByteArray? = localVideoBytes(media3, file, retriever, retriever.readDuration(), targetWidth)

private suspend fun localVideoBytes(
    media3: Media3VideoFrameExtractor,
    file: MediaFile,
    retriever: MediaMetadataRetriever,
    duration: PreviewDuration,
    targetWidth: Int,
): ByteArray? {
    // 时长读不到时改用固定候选位置，不因此丢弃已经能解出的帧。
    val positions = framePositions((duration as? PreviewDuration.Known)?.millis ?: 0, LOCAL_FRAME_COUNT)
    return decodeStage("视频抽帧失败") { media3.localImage(file, positions, targetWidth) }
}

/** 网络视频：时长用于挑候选位置；抽帧走 Media3，候选全不合格时保持占位图。 */
private suspend fun networkVideoFrameBytes(
    media3: Media3VideoFrameExtractor,
    source: ByteSource,
    file: MediaFile,
    targetWidth: Int,
): ByteArray? = decodeStage("视频抽帧失败") {
    media3.networkImage(file, source, networkFrameCandidates(readNetworkDuration(source)), targetWidth)
}

/** 只读网络时长；读不到时返回 null，候选位置退回 1 秒。 */
private fun readNetworkDuration(source: ByteSource): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(NonClosingMediaDataSource(source))
        readDurationOrNull(retriever)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    } finally {
        retriever.release()
    }
}

/** 读取时长；读不到时返回 null，由调用方决定兜底位置。 */
private fun readDurationOrNull(retriever: MediaMetadataRetriever): Long? = try {
    durationMillis(retriever)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (unreadable: Exception) {
    null
}

/** 内嵌封面按与视频帧相同的宽度和 JPEG 质量处理；解码失败按「解码」阶段收尾。 */
private fun encodeCover(picture: ByteArray, targetWidth: Int): ByteArray {
    // 先读尺寸并按 2 的幂降采样，避免超大封面先解出全尺寸位图。
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw PreviewStageException(PreviewFailureStage.Decode, "封面解码失败")
    }
    val target = targetFrameWidth(bounds.outWidth, targetWidth)
    val options = BitmapFactory.Options().apply {
        inSampleSize = coverSampleSize(bounds.outWidth, target)
    }
    val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size, options)
        ?: throw PreviewStageException(PreviewFailureStage.Decode, "封面解码失败")
    return try {
        val scaled = scaleFrame(bitmap, targetWidth)
        try {
            compressFrame(scaled)
                ?: throw PreviewStageException(PreviewFailureStage.Decode, "封面压缩失败")
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}

/** 系统内嵌图；读取失败按没有处理，交给 ID3v2 兜底。 */
private fun embeddedPictureOrNull(retriever: MediaMetadataRetriever): ByteArray? = try {
    retriever.embeddedPicture
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    null
}

/** ID3 解析结果的日志标签。 */
private fun id3Label(result: Id3CoverResult): String = when (result) {
    is Id3CoverResult.Picture -> "取到封面"
    is Id3CoverResult.Missing -> result.reason.name
    is Id3CoverResult.Failed -> result.reason.name
}

/** 本地 SAF 文件的 ID3 标签只读头部：只读标签头与需要的帧，不整文件读。 */
private fun readLocalId3Cover(context: Context, file: MediaFile): Id3CoverResult {
    val descriptor = try {
        context.contentResolver.openFileDescriptor(file.location.toUri(), "r")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        return Id3CoverResult.Failed(Id3CoverFailure.ReadFailed)
    } ?: return Id3CoverResult.Failed(Id3CoverFailure.ReadFailed)
    return descriptor.use { opened ->
        val size = opened.statSize.takeIf { it > 0 } ?: file.size
        if (size <= 0) {
            Id3CoverResult.Missing(Id3CoverMiss.NotId3)
        } else {
            readId3Cover(ContentUriCoverSource(opened, size))
        }
    }
}

/** 本地 SAF 文件的随机读：按位置读标签段，不整文件读。 */
private class ContentUriCoverSource(
    private val descriptor: ParcelFileDescriptor,
    override val size: Long,
) : CoverByteSource {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size || length <= 0) return 0
        val read = Os.pread(descriptor.fileDescriptor, buffer, offset, length, position)
        return if (read < 0) 0 else read
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
internal fun scaleFrame(frame: Bitmap, targetWidth: Int): Bitmap {
    val width = targetFrameWidth(frame.width, targetWidth)
    if (width <= 0 || width == frame.width) return frame
    val height = maxOf(1, (frame.height.toLong() * width / frame.width).toInt())
    return frame.scale(width, height, filter = true).also { frame.recycle() }
}

internal fun compressFrame(frame: Bitmap): ByteArray? = ByteArrayOutputStream().use { output ->
    if (frame.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, output)) {
        output.toByteArray()
    } else {
        null
    }
}

/** 封面可用性：在缩略图上按网格采样亮度，纯色与近黑帧都不可用。 */
internal fun isUnusableFrame(frame: Bitmap): Boolean = isUnusableLuminance(luminanceSamples(frame))

/** 近黑判据单独暴露给候选选择：兜底帧也不能是近黑帧。 */
internal fun isDarkFrame(frame: Bitmap): Boolean = isDarkLuminance(luminanceSamples(frame))

private fun luminanceSamples(frame: Bitmap): IntArray {
    if (frame.width <= 0 || frame.height <= 0) return IntArray(0)
    val columns = 16
    val rows = 16
    return IntArray(columns * rows) { index ->
        val column = index % columns
        val row = index / columns
        val x = column * (frame.width - 1) / (columns - 1)
        val y = row * (frame.height - 1) / (rows - 1)
        luminance(frame[x, y])
    }
}

private fun luminance(pixel: Int): Int {
    val red = (pixel shr 16) and 0xFF
    val green = (pixel shr 8) and 0xFF
    val blue = pixel and 0xFF
    return (red * 299 + green * 587 + blue * 114) / 1000
}
