package com.cc3301.ccplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.streaming.ByteSource
import com.google.common.util.concurrent.ListenableFuture
import java.io.EOFException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 单帧抽帧的等待上限；超时只停止等待，在途任务由数据源传输超时与句柄关闭收尾。 */
internal const val FRAME_TIMEOUT_MILLIS = 10_000L

/** 本地整次抽帧的上限：打开数据源与候选解码合计不能无限期挂住。 */
internal const val LOCAL_EXTRACT_TIMEOUT_MILLIS = 20_000L

/**
 * 视频抽帧：走 Media3 的 FrameExtractor，本地与网络用同一条路径。
 *
 * 网络数据源由 [ByteSourceDataSource] 包装现有随机读句柄，复用 [PreviewByteWindow] 的预读；
 * 本地交给 Media3 的默认数据源（SAF content URI）。封装解析、寻址与解码都由 FrameExtractor 负责，
 * 候选时间点、纯色与近黑剔除仍由本模块决定。FrameExtractor 的内部播放器是进程内单实例，
 * 抽帧请求按提交顺序串行执行；实例只在主线程访问，避免跨线程操作内部播放器。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class Media3VideoFrameExtractor(
    private val context: Context,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) {

    /** 本地视频：按候选位置解码，取第一张可用帧；候选帧都不可用时按抽取失败处理。 */
    suspend fun localImage(file: MediaFile, positions: List<FramePosition>, targetWidth: Int): ByteArray? {
        val decoded = withTimeoutOrNull(LOCAL_EXTRACT_TIMEOUT_MILLIS) {
            withContext(Dispatchers.Main.immediate) {
                decodeLocal(file, positions, targetWidth)
            }
        }
        if (decoded == null) {
            diagnostics.log { "视频封面: ${file.name} 抽帧超时" }
            throw PreviewStageException(PreviewFailureStage.Timeout, "视频抽帧超时")
        }
        if (decoded.isEmpty()) throw PreviewStageException(PreviewFailureStage.Decode, "视频抽帧失败")
        val chosen = requireDecodedFrame(decoded, ::isUnusableFrame, ::isDarkFrame)
        return compress(chosen, decoded)
    }

    /** 网络视频：按候选位置逐个解码，取第一张可用帧；全部不合格时返回 null（保持占位图）。 */
    suspend fun networkImage(
        file: MediaFile,
        source: ByteSource,
        positions: List<Long>,
        targetWidth: Int,
    ): ByteArray? {
        val chosen = withContext(Dispatchers.Main.immediate) {
            decodeNetwork(file, source, positions)
        } ?: return null
        return try {
            val scaled = scaleFrame(chosen, targetWidth)
            try {
                compressFrame(scaled)
                    ?: throw PreviewStageException(PreviewFailureStage.Decode, "视频帧压缩失败")
            } finally {
                scaled.recycle()
            }
        } finally {
            chosen.recycle()
        }
    }

    private suspend fun decodeLocal(
        file: MediaFile,
        positions: List<FramePosition>,
        targetWidth: Int,
    ): List<Pair<FramePosition, Bitmap>> {
        val extractor = buildExtractor(file, localMediaSourceFactory(file))
        return try {
            // 每帧解出后立刻缩放，避免同时持有三张全尺寸位图；缩放不占主线程。
            positions.mapNotNull { position ->
                val bitmap = frameAt(extractor, position.micros, file.name) ?: return@mapNotNull null
                position to withContext(Dispatchers.Default) { scaleFrame(bitmap, targetWidth) }
            }
        } finally {
            // 超时或取消不取消 future：取消会打乱 Media3 的串行队列；关闭句柄让在途读取以错误收尾。
            extractor.close()
        }
    }

    private suspend fun decodeNetwork(
        file: MediaFile,
        source: ByteSource,
        positions: List<Long>,
    ): Bitmap? {
        val extractor = buildExtractor(file, networkMediaSourceFactory(file, source))
        return try {
            extractNetworkFrame(
                positions = positions,
                decode = { micros -> frameAt(extractor, micros, file.name) },
                isSolid = ::isUnusableFrame,
                isDark = ::isDarkFrame,
                release = Bitmap::recycle,
            )
        } finally {
            // 超时或取消不取消 future：取消会打乱 Media3 的串行队列；关闭句柄让在途读取以错误收尾。
            extractor.close()
        }
    }

    private fun buildExtractor(file: MediaFile, mediaSourceFactory: MediaSource.Factory): FrameExtractor =
        FrameExtractor.Builder(context, MediaItem.fromUri(file.location))
            .setMediaSourceFactory(mediaSourceFactory)
            // 与系统取帧保持一致：只取候选位置附近的关键帧，不做逐帧精确寻址。
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()

    /** 取一帧；超时抛超时阶段，解码异常按解码阶段收尾。取消原样抛出。 */
    private suspend fun frameAt(extractor: FrameExtractor, micros: Long, name: String): Bitmap? {
        val frame = try {
            withTimeoutOrNull(FRAME_TIMEOUT_MILLIS) {
                extractor.getFrame(micros / 1_000).awaitFrame()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw PreviewStageException(PreviewFailureStage.Decode, "视频抽帧失败", failure)
        }
        if (frame == null) {
            diagnostics.log { "视频封面: $name 抽帧超时" }
            throw PreviewStageException(PreviewFailureStage.Timeout, "视频抽帧超时")
        }
        return frame.bitmap
    }

    /**
     * 等待 Media3 的抽帧任务；只停止等待，不取消在途任务。
     *
     * 取消 future 会让内部串行队列与仍在跑的抽帧错位，后续请求会以「已有任务在跑」失败；
     * 超时或离屏时保留任务，由数据源传输超时与句柄关闭收尾。
     */
    private suspend fun ListenableFuture<FrameExtractor.Frame>.awaitFrame(): FrameExtractor.Frame =
        suspendCancellableCoroutine { continuation ->
            addListener(
                {
                    // 超时或取消后协程已结束：不再回填结果，也不能对已取消的续体重复恢复。
                    if (continuation.isActive) {
                        try {
                            continuation.resume(get())
                        } catch (failure: ExecutionException) {
                            continuation.resumeWithException(failure.cause ?: failure)
                        } catch (failure: Throwable) {
                            continuation.resumeWithException(failure)
                        }
                    }
                },
                DIRECT_EXECUTOR,
            )
        }

    private fun localMediaSourceFactory(file: MediaFile): MediaSource.Factory {
        val factory = DataSource.Factory { DefaultDataSource.Factory(context).createDataSource() }
        return DefaultMediaSourceFactory(factory)
    }

    private fun networkMediaSourceFactory(file: MediaFile, source: ByteSource): MediaSource.Factory {
        val uri = file.location.toUri()
        val factory = DataSource.Factory { ByteSourceDataSource(source, uri) }
        return DefaultMediaSourceFactory(factory)
    }

    /** 候选帧在解码时已经缩放，这里只压缩并回收；不再二次缩放。 */
    private fun compress(
        chosen: Bitmap,
        decoded: List<Pair<FramePosition, Bitmap>>,
    ): ByteArray = try {
        compressFrame(chosen)
            ?: throw PreviewStageException(PreviewFailureStage.Decode, "视频帧压缩失败")
    } finally {
        chosen.recycle()
        decoded.forEach { (_, bitmap) ->
            if (bitmap !== chosen) bitmap.recycle()
        }
    }

    private companion object {
        /** Media3 的 future 在播放器线程完成；这里直接在这一线程上恢复协程。 */
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}

/** 把现有随机读句柄接到 Media3 的数据源接口上；句柄的关闭责任仍在预览读取层。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ByteSourceDataSource(
    private val source: ByteSource,
    private val uri: Uri?,
) : DataSource {

    private val reader = ByteSourceReadCursor(source)

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long = reader.open(dataSpec.position)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = reader.read(buffer, offset, length)

    override fun getUri(): Uri = uri ?: Uri.EMPTY

    override fun close() = reader.close()
}

/** 随机读游标：把一次读取映射成 Media3 的返回值，关闭不关闭底层句柄。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ByteSourceReadCursor(private val source: ByteSource) {

    private var position = 0L
    private var opened = false

    /** 从 [position] 开始顺序读；返回剩余长度，越界按读出末尾处理。 */
    fun open(position: Long): Long {
        if (position < 0 || position > source.size) throw EOFException("读取位置超出文件长度")
        this.position = position
        opened = true
        return (source.size - position).coerceAtLeast(0)
    }

    /** 读到末尾或短读按实际字节返回；返回 0 只用于 length 为 0。 */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT
        val read = rangeReadAt(source, position, buffer, offset, length)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        return read
    }

    fun close() {
        opened = false
    }
}
