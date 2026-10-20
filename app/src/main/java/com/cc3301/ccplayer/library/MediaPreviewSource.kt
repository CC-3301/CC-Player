package com.cc3301.ccplayer.library

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 卡片预览接缝：列表界面只依赖本接口，本地与网络实现由此注入。
 */
interface MediaPreviewSource {

    /** 取卡片预览；目录没有预览。 */
    suspend fun preview(file: MediaFile): MediaPreview
}

/** 卡片预览：预览图（null 时界面用占位图）与时长（null 时不显示角标）。 */
data class MediaPreview(val image: File?, val durationMillis: Long?)

/**
 * 从媒体文件里取预览图与时长；实现依赖 Android 解码器，测试用假实现替换。
 */
interface MediaPreviewExtractor {

    /** 取预览图字节：视频是首帧附近的一帧，音频是文件内嵌封面；没有可用图时返回 null。 */
    suspend fun imageBytes(file: MediaFile): ByteArray?

    /** 取时长（毫秒）；元数据里没有时长时返回 null。 */
    suspend fun durationMillis(file: MediaFile): Long?
}

/**
 * 媒体预览：先在 [cache] 里找图，没有就让 [extractor] 抽一次图并落盘。
 *
 * 网络条目的缓存键含修改时间，修改时间变化视为新文件；并发提取数有上限，
 * 失败或超时保持占位图且不写缓存。
 */
class CachedMediaPreviewSource(
    private val extractor: MediaPreviewExtractor,
    private val cache: ThumbnailCache,
) : MediaPreviewSource {

    /** 网络提取的同时请求数上限：一次进入目录时不发起过多网络请求。 */
    private val networkPermits = Semaphore(NETWORK_CONCURRENCY)

    override suspend fun preview(file: MediaFile): MediaPreview {
        if (file.isDirectory) return MediaPreview(image = null, durationMillis = null)
        return when (file.source) {
            MediaSource.Local -> MediaPreview(
                image = cachedImage(file, file.location),
                durationMillis = extractor.durationMillis(file),
            )

            MediaSource.Network -> MediaPreview(image = networkImage(file), durationMillis = null)
        }
    }

    private suspend fun cachedImage(file: MediaFile, key: String): File? {
        cache.file(key)?.let { return it }
        val bytes = extractor.imageBytes(file) ?: return null
        return cache.save(key, bytes)
    }

    private suspend fun networkImage(file: MediaFile): File? {
        val key = networkCacheKey(file)
        cache.file(key)?.let { return it }
        val bytes = try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) {
                networkPermits.withPermit { extractor.imageBytes(file) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        } ?: return null
        return cache.save(key, bytes)
    }

    companion object {
        /** 缩略图缓存目录的上限。 */
        private const val CACHE_BYTES = 64L * 1024 * 1024

        /** 同时进行的网络提取数；超出的由 [networkPermits] 排队。 */
        private const val NETWORK_CONCURRENCY = 3

        /** 单次网络提取的等待上限；超时按没有图处理。 */
        private const val NETWORK_TIMEOUT_MILLIS = 15_000L

        fun create(context: Context, networkReads: NetworkMediaReads): CachedMediaPreviewSource {
            val appContext = context.applicationContext
            return CachedMediaPreviewSource(
                extractor = AndroidMediaPreviewExtractor(appContext, networkReads),
                cache = ThumbnailCache(appContext.filesDir.resolve("thumbnails"), CACHE_BYTES),
            )
        }

        /** 网络缓存键：媒体位置 + 修改时间；位置相同但修改时间变了就是新文件。 */
        private fun networkCacheKey(file: MediaFile): String = "${file.location}@${file.lastModified}"
    }
}
