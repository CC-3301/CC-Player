package com.cc3301.ccplayer.library

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** 一次读取会话取到的预览数据。 */
data class MediaPreviewExtraction(val imageBytes: ByteArray?, val durationMillis: Long?)

/**
 * 从媒体文件里取预览图与时长；实现依赖 Android 解码器，测试用假实现替换。
 */
interface MediaPreviewExtractor {

    /** 取预览图字节：视频按策略抽帧，音频是文件内嵌封面；没有可用图时返回 null。 */
    suspend fun imageBytes(file: MediaFile): ByteArray?

    /** 取时长（毫秒）；元数据里没有时长时返回 null。 */
    suspend fun durationMillis(file: MediaFile): Long?

    /** 在同一次读取会话里取图与时长；默认分两次取，真实实现覆盖为一次。 */
    suspend fun imageAndDuration(file: MediaFile): MediaPreviewExtraction =
        MediaPreviewExtraction(imageBytes(file), durationMillis(file))
}

/** 缓存键使用的抽帧策略；任一项变化都视为另一份缓存。 */
data class ThumbnailPolicy(
    val version: Int = THUMBNAIL_POLICY_VERSION,
    val targetWidth: Int = THUMBNAIL_TARGET_WIDTH,
    val missingModifiedValidityMillis: Long = MISSING_MODIFIED_VALIDITY_MILLIS,
)

/**
 * 媒体预览：先在 [cache] 里找图，没有就让 [extractor] 抽取并落盘。
 *
 * 缓存键包含位置、大小、修改时间、策略版本与目标宽度；修改时间缺失时退化为大小与有效期。
 * 本地与网络并发提取各有上限，同一缓存键的并发请求只抽取一次。失败或超时保持占位图且不写缓存。
 */
class CachedMediaPreviewSource(
    private val extractor: MediaPreviewExtractor,
    private val cache: ThumbnailCache,
    private val policy: ThumbnailPolicy = ThumbnailPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaPreviewSource {

    /** 本地同时提取数上限。 */
    private val localPermits = Semaphore(LOCAL_CONCURRENCY)

    /** 网络同时提取数上限：一次进入目录时不发起过多网络请求。 */
    private val networkPermits = Semaphore(NETWORK_CONCURRENCY)

    /** 同一缓存键正在进行的抽取；等待者共享同一次结果。 */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<LoadedPreview>>()

    override suspend fun preview(file: MediaFile): MediaPreview {
        if (file.isDirectory) return MediaPreview(image = null, durationMillis = null)
        val key = thumbnailCacheKey(file, policy, clock())
        return when (file.source) {
            MediaSource.Local -> localPreview(file, key)
            MediaSource.Network -> MediaPreview(image = networkImage(file, key), durationMillis = null)
        }
    }

    private suspend fun localPreview(file: MediaFile, key: String): MediaPreview {
        val loaded = cachedOrLoad(
            key = key,
            onCacheHit = { cached -> LoadedPreview(cached, localDuration(file)) },
            load = {
                val extracted = localPermits.withPermit { extractor.imageAndDuration(file) }
                LoadedPreview(
                    image = extracted.imageBytes?.let { bytes -> cache.save(key, bytes) },
                    durationMillis = extracted.durationMillis,
                )
            },
        )
        return MediaPreview(loaded.image, loaded.durationMillis)
    }

    private suspend fun localDuration(file: MediaFile): Long? =
        localPermits.withPermit { extractor.durationMillis(file) }

    private suspend fun networkImage(file: MediaFile, key: String): File? {
        val loaded = cachedOrLoad(
            key = key,
            onCacheHit = { cached -> LoadedPreview(cached, durationMillis = null) },
            load = {
                val bytes = try {
                    networkPermits.withPermit {
                        // 可取消的超时；排队时间不计入，阻塞中的网络读由各自的传输层超时兜底。
                        withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) { extractor.imageBytes(file) }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }
                LoadedPreview(bytes?.let { cache.save(key, it) }, durationMillis = null)
            },
        )
        return loaded.image
    }

    /**
     * 先查缓存；没有就让第一个调用者抽取，同一缓存键的并发调用等待同一结果。
     * 首个调用者被取消时，等待者重新竞争，避免把取消当成抽取失败。
     */
    private suspend fun cachedOrLoad(
        key: String,
        onCacheHit: suspend (File) -> LoadedPreview,
        load: suspend () -> LoadedPreview,
    ): LoadedPreview {
        while (true) {
            cache.file(key)?.let { return onCacheHit(it) }
            inFlight[key]?.let { pending ->
                try {
                    return pending.await()
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // 首个调用者已取消且未完成，重新竞争抽取。
                }
            }
            val mine = CompletableDeferred<LoadedPreview>()
            if (inFlight.putIfAbsent(key, mine) != null) continue
            try {
                val loaded = try {
                    cache.file(key)?.let { cached -> onCacheHit(cached) } ?: load()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    LoadedPreview(image = null, durationMillis = null)
                }
                mine.complete(loaded)
                return loaded
            } catch (cancellation: CancellationException) {
                mine.cancel(cancellation)
                throw cancellation
            } finally {
                inFlight.remove(key, mine)
            }
        }
    }

    private data class LoadedPreview(val image: File?, val durationMillis: Long?)

    companion object {
        /** 缩略图缓存目录的上限。 */
        private const val CACHE_BYTES = 192L * 1024 * 1024

        /** 同时进行的本地提取数；超出的排队。 */
        private const val LOCAL_CONCURRENCY = 2

        /** 同时进行的网络提取数；超出的由 [networkPermits] 排队。 */
        private const val NETWORK_CONCURRENCY = 3

        /** 单次网络提取的等待上限；排队时间不计入。 */
        private const val NETWORK_TIMEOUT_MILLIS = 15_000L

        fun create(context: Context, networkReads: NetworkMediaReads): CachedMediaPreviewSource {
            val appContext = context.applicationContext
            val policy = ThumbnailPolicy()
            return CachedMediaPreviewSource(
                extractor = AndroidMediaPreviewExtractor(appContext, networkReads, policy.targetWidth),
                cache = ThumbnailCache(appContext.filesDir.resolve("thumbnails"), CACHE_BYTES),
                policy = policy,
            )
        }
    }
}

/**
 * 缩略图缓存键：位置、大小、修改时间、抽帧策略版本与目标宽度。
 * 修改时间缺失时用大小与有效期时段代替修改时间。
 */
internal fun thumbnailCacheKey(file: MediaFile, policy: ThumbnailPolicy, now: Long): String {
    val base = "${file.location}|${file.size}|v${policy.version}|w${policy.targetWidth}"
    return if (file.lastModified > 0) {
        "$base|m${file.lastModified}"
    } else {
        val validity = policy.missingModifiedValidityMillis.coerceAtLeast(1)
        "$base|t${now / validity}"
    }
}
