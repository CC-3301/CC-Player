package com.cc3301.ccplayer.library

import android.content.Context
import java.io.File

/**
 * 卡片预览接缝：列表界面只依赖本接口，本地抽帧实现由此注入。
 */
interface MediaPreviewSource {

    /** 取卡片预览；目录与网络源没有预览。 */
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
 * 本地媒体预览：先在 [cache] 里找图，没有就让 [extractor] 抽一次图并落盘。
 * 网络文件不抽帧（ADR-0021）。
 */
class CachedMediaPreviewSource(
    private val extractor: MediaPreviewExtractor,
    private val cache: ThumbnailCache,
) : MediaPreviewSource {

    override suspend fun preview(file: MediaFile): MediaPreview {
        if (file.isDirectory || file.source != MediaSource.Local) return MediaPreview(image = null, durationMillis = null)
        return MediaPreview(
            image = cachedImage(file),
            durationMillis = extractor.durationMillis(file),
        )
    }

    private suspend fun cachedImage(file: MediaFile): File? {
        cache.file(file.location)?.let { return it }
        val bytes = extractor.imageBytes(file) ?: return null
        return cache.save(file.location, bytes)
    }

    companion object {
        /** 缩略图缓存目录的上限。 */
        private const val CACHE_BYTES = 64L * 1024 * 1024

        fun create(context: Context): CachedMediaPreviewSource {
            val appContext = context.applicationContext
            return CachedMediaPreviewSource(
                extractor = AndroidMediaPreviewExtractor(appContext),
                cache = ThumbnailCache(appContext.filesDir.resolve("thumbnails"), CACHE_BYTES),
            )
        }
    }
}
