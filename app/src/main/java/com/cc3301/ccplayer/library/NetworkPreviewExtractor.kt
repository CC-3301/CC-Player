package com.cc3301.ccplayer.library

import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.streaming.ByteSource
import com.cc3301.ccplayer.streaming.NetworkReadPriority
import com.cc3301.ccplayer.streaming.ReadKind
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 网络预览解码接缝：真实现用 MediaMetadataRetriever 读取字节范围，测试用假实现。
 *
 * 实现不得关闭 [source]；句柄统一由 [NetworkPreviewPreparation] 关闭。
 */
fun interface NetworkPreviewDecoder {

    /** 从 [source] 取图；没有可用图时返回 null。 */
    fun imageBytes(source: ByteSource, kind: MediaKind, targetWidth: Int): ByteArray?
}

/**
 * 已打开句柄、已发起定点预读的网络取图。
 *
 * [decode] 只跑原生解码，由调用方放在解码许可内；字节预读已经在许可外发起，并在解码期间继续推进。
 * 解码与关闭都在 IO 线程执行，不跟随调用方所在线程。用完必须 [close]：解码成功、失败或取消都要关句柄。
 */
class NetworkPreviewPreparation(
    private val decodeBlock: suspend (Int) -> ByteArray?,
    private val closeBlock: suspend () -> Unit,
) {

    /** 在解码许可内取图；没有可用图时返回 null。 */
    suspend fun decode(targetWidth: Int): ByteArray? = decodeBlock(targetWidth)

    /** 关闭句柄与预读；可重复调用。取消时也要关，不能随请求一起丢弃。 */
    suspend fun close() = closeBlock()
}

/**
 * 网络预览取图：打开随机读句柄后用 [PreviewByteWindow] 包成本地字节，再交给 [decoder]。
 *
 * 句柄长度取自目录列表，打开时不发请求取长度；预读用另一条独立句柄，
 * 解码与预读在同一条流上交替位置会重发区间请求。预读按元数据阶段定点读发起，之后跟随解码命中。
 * 打开与预读不占解码许可，解码由 [NetworkPreviewPreparation.decode] 在许可内执行。
 * 打开失败或取消时两条句柄都会关闭；取消或超时时先关闭句柄，让阻塞中的传输读取退出。
 * 读取按 [readPriority] 让出给播放读取，两条句柄的等待合计成一条诊断。打开或解码失败抛出异常，
 * 由上层保持占位图并重试。
 */
class NetworkPreviewExtractor(
    private val reads: NetworkMediaReads,
    private val readPriority: NetworkReadPriority = NetworkReadPriority.shared,
    private val decoder: NetworkPreviewDecoder,
    private val readAhead: PreviewReadAhead = previewReadAhead,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) {

    /** 仅测试使用；生产的网络取图只走 [prepare]。 */
    suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray? {
        val prepared = prepare(file) ?: return null
        try {
            return prepared.decode(targetWidth)
        } finally {
            prepared.close()
        }
    }

    /**
     * 打开句柄并起定点预读；不占解码许可。失败或取消时关闭已打开的两条句柄。
     * [readAheadRank] 是这条目的预读派发顺序：可见项按列表索引，预取排在可见之后。
     */
    suspend fun prepare(
        file: MediaFile,
        readAheadRank: Long = PREVIEW_PREFETCH_RANK,
    ): NetworkPreviewPreparation? {
        val waitedMillis = AtomicLong()
        var window: PreviewByteWindow? = null
        var primary: ByteSource? = null
        var readAheadHandle: ByteSource? = null
        try {
            return withContext(Dispatchers.IO) {
                val kind = mediaKindOf(file.name) ?: return@withContext null
                val source = readPriority.wrap(reads.openRead(file.location, file.size), ReadKind.Thumbnail) { millis ->
                    waitedMillis.addAndGet(millis)
                }
                primary = source
                readAheadHandle = openReadAhead(file, waitedMillis)
                val previewWindow = PreviewByteWindow(
                    origin = source,
                    readAheadSource = readAheadHandle,
                    readAhead = readAhead,
                    readAheadRank = readAheadRank,
                )
                window = previewWindow
                previewWindow.startMetadataReadAhead()
                NetworkPreviewPreparation(
                    // 系统抽帧器在调用线程上回调读字节：解码固定在 `Dispatchers.IO` 上发起，不跟随调用方所在线程。
                    decodeBlock = { targetWidth ->
                        withContext(Dispatchers.IO) {
                            try {
                                decode(previewWindow, kind, targetWidth)
                            } finally {
                                // 让给播放读取的时长：与取许可等待分开记，能区分排队与等播放读；两条句柄合计。
                                diagnostics.log { "网络封面: ${file.name} 等播放读 ${waitedMillis.get()}ms" }
                            }
                        }
                    },
                    closeBlock = {
                        withContext(NonCancellable + Dispatchers.IO) { runCatching { previewWindow.close() } }
                    },
                )
            }
        } catch (failure: Throwable) {
            // 取消可能在 withContext 返回后才生效并丢弃结果：已打开的资源在这里兜底关闭。
            // 关闭句柄可能阻塞在网络上：取消时也要执行，固定在 `Dispatchers.IO` 上收尾。
            withContext(NonCancellable + Dispatchers.IO) {
                val openedWindow = window
                if (openedWindow != null) {
                    // 窗口已建、准备还没交出去：关窗口，已提交的预读不再回写，两条句柄一起关。
                    runCatching { openedWindow.close() }
                } else {
                    runCatching { primary?.close() }
                    runCatching { readAheadHandle?.close() }
                }
            }
            throw failure
        }
    }

    /** 预读用的第二条句柄；让给播放读取的时长与主句柄一起累计。打不开就退回与解码共用一条。 */
    private suspend fun openReadAhead(file: MediaFile, waitedMillis: AtomicLong): ByteSource? = try {
        readPriority.wrap(reads.openRead(file.location, file.size), ReadKind.Thumbnail) { millis ->
            waitedMillis.addAndGet(millis)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
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
