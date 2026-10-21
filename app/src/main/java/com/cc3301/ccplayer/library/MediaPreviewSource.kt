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

/** 一次读取会话得到的时长结果：已知值、确定没有、读取失败。 */
sealed interface PreviewDuration {
    data class Known(val millis: Long) : PreviewDuration
    object Unavailable : PreviewDuration
    object Failed : PreviewDuration
}

/** 一次读取会话取到的预览数据；图片为 null 表示没有可用图。 */
data class MediaPreviewExtraction(val imageBytes: ByteArray?, val duration: PreviewDuration) {
    val durationMillis: Long? get() = (duration as? PreviewDuration.Known)?.millis
}

/**
 * 从媒体文件里取预览图与时长；实现依赖 Android 解码器，测试用假实现替换。
 */
interface MediaPreviewExtractor {

    /** 取预览图字节：视频按策略抽帧，音频是文件内嵌封面；没有可用图时返回 null，读取失败抛出异常。 */
    suspend fun imageBytes(file: MediaFile, targetWidth: Int = THUMBNAIL_TARGET_WIDTH): ByteArray?

    /** 取时长（毫秒）；元数据里没有时长时返回 null，读取失败抛出异常。 */
    suspend fun durationMillis(file: MediaFile): Long?

    /** 在同一次读取会话里取图与时长；时长读取失败不影响已解出的图片。 */
    suspend fun imageAndDuration(
        file: MediaFile,
        targetWidth: Int = THUMBNAIL_TARGET_WIDTH,
    ): MediaPreviewExtraction {
        val image = imageBytes(file, targetWidth)
        val duration = try {
            when (val millis = durationMillis(file)) {
                null -> PreviewDuration.Unavailable
                else -> PreviewDuration.Known(millis)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            PreviewDuration.Failed
        }
        return MediaPreviewExtraction(image, duration)
    }
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
 * 本地与网络并发提取各有上限，同一缓存键的并发请求只抽取一次。
 * 失败或超时保持占位图且不写缓存；失败按键退避，退避到期后下一次请求重试。
 */
class CachedMediaPreviewSource(
    private val extractor: MediaPreviewExtractor,
    private val cache: ThumbnailCache,
    private val policy: ThumbnailPolicy = ThumbnailPolicy(),
    private val monotonicClock: () -> Long = System::nanoTime,
) : MediaPreviewSource {

    /** 本地同时提取数上限。 */
    private val localPermits = Semaphore(LOCAL_CONCURRENCY)

    /** 网络同时提取数上限：一次进入目录时不发起过多网络请求。 */
    private val networkPermits = Semaphore(NETWORK_CONCURRENCY)

    /** 同一缓存键正在进行的抽取；等待者共享同一次结果。 */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<LoadedPreview>>()

    /** 整次抽取失败后的退避记录；按缓存键分开，成功后清理。 */
    private val extractionFailures = ConcurrentHashMap<String, RetryState>()

    /** 命中后补读时长失败后的退避记录；与整次抽取退避分开。 */
    private val durationFailures = ConcurrentHashMap<String, RetryState>()

    /** 实际交给抽取器的目标宽度；策略值先钳到 320–512。 */
    private val targetWidth = effectiveTargetWidth(policy.targetWidth)

    override suspend fun preview(file: MediaFile): MediaPreview {
        if (file.isDirectory) return MediaPreview(image = null, durationMillis = null)
        val key = thumbnailCacheKey(file, policy)
        return when (file.source) {
            MediaSource.Local -> localPreview(file, key)
            MediaSource.Network -> MediaPreview(image = networkImage(file, key), durationMillis = null)
        }
    }

    private suspend fun localPreview(file: MediaFile, key: String): MediaPreview {
        val maxAge = cacheMaxAge(file)
        val loaded = cachedOrLoad(
            key = key,
            maxAgeMillis = maxAge,
            onCacheHit = { cached -> durationOnCacheHit(file, key, cached) },
            load = { extractLocal(file, key) },
        )
        return MediaPreview(loaded.image, loaded.durationMillis)
    }

    /** 命中缓存时复用已记录的时长；未知时才补读，失败只影响角标。 */
    private suspend fun durationOnCacheHit(
        file: MediaFile,
        key: String,
        cached: CachedThumbnail,
    ): LoadedPreview {
        if (cached.durationKnown) return LoadedPreview(cached.file, cached.durationMillis)
        return when (val duration = readLocalDuration(file, key)) {
            is PreviewDuration.Known -> {
                writeDurationSafely(key, duration.millis)
                LoadedPreview(cached.file, duration.millis)
            }

            PreviewDuration.Unavailable -> {
                // 确定没有时长：写入后，后续命中不再重读。
                writeDurationSafely(key, null)
                LoadedPreview(cached.file, durationMillis = null)
            }

            // 读取失败只影响角标；不落盘，退避后重试。
            PreviewDuration.Failed -> LoadedPreview(cached.file, durationMillis = null)
        }
    }

    /** 一次读取会话取图与时长；无图条目也记录时长，避免下次重开文件。 */
    private suspend fun extractLocal(file: MediaFile, key: String): LoadedPreview {
        val extracted = localPermits.withPermit { extractor.imageAndDuration(file, targetWidth) }
        // 解码阻塞期间无法中断；返回后若已取消，丢弃结果，避免离屏请求落盘。
        currentCoroutineContext().ensureActive()
        val durationKnown = extracted.duration != PreviewDuration.Failed
        val durationMillis = extracted.durationMillis
        val image = extracted.imageBytes?.let { bytes ->
            cache.save(
                location = key,
                bytes = bytes,
                durationMillis = durationMillis,
                durationKnown = durationKnown,
            )
        }
        if (image == null) {
            // 无图条目只记时长；时长读取失败时记“未知”，下次命中再补读。
            cache.saveWithoutImage(
                location = key,
                durationMillis = durationMillis,
                durationKnown = durationKnown,
            )
        }
        return LoadedPreview(image, durationMillis)
    }

    /** 读时长失败只影响角标；失败后退避，避免慢源每次命中都重试并长期占用本地名额。 */
    private suspend fun readLocalDuration(file: MediaFile, key: String): PreviewDuration {
        val now = monotonicClock()
        val previous = durationFailures[key]
        if (previous != null && now < previous.retryAtMillis) return PreviewDuration.Failed
        val result = try {
            when (val millis = localPermits.withPermit { extractor.durationMillis(file) }) {
                null -> PreviewDuration.Unavailable
                else -> PreviewDuration.Known(millis)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            PreviewDuration.Failed
        }
        if (result == PreviewDuration.Failed) {
            val attempts = (previous?.attempts ?: 0) + 1
            val failedAt = monotonicClock()
            recordRetry(
                failures = durationFailures,
                key = key,
                state = RetryState(attempts, failedAt + durationRetryDelay(attempts)),
                now = failedAt,
            )
        } else {
            durationFailures.remove(key)
        }
        return result
    }

    /** 连续失败按倍数退避，上限为十分钟。 */
    private fun durationRetryDelay(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 6)
        return (DURATION_RETRY_BASE_MILLIS shl shift).coerceAtMost(DURATION_RETRY_MAX_MILLIS)
    }

    private suspend fun writeDurationSafely(key: String, durationMillis: Long?) {
        try {
            cache.writeDuration(key, durationMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // 回写失败不影响本次图片返回，下次命中仍会重试。
        }
    }

    private suspend fun networkImage(file: MediaFile, key: String): File? {
        val maxAge = cacheMaxAge(file)
        val loaded = cachedOrLoad(
            key = key,
            maxAgeMillis = maxAge,
            onCacheHit = { cached -> LoadedPreview(cached.file, durationMillis = null) },
            load = {
                // 排队时间不计入超时；阻塞中的网络读由各自的传输层超时兜底。
                val (completed, bytes) = networkPermits.withPermit {
                    var decoded: ByteArray? = null
                    val done = withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) {
                        decoded = extractor.imageBytes(file, targetWidth)
                        true
                    } ?: false
                    done to decoded
                }
                // 解码阻塞期间无法中断；返回后若已取消，丢弃结果，避免离屏请求落盘。
                currentCoroutineContext().ensureActive()
                if (!completed) throw IllegalStateException("网络预览超时")
                LoadedPreview(bytes?.let { cache.save(key, it) }, durationMillis = null)
            },
        )
        return loaded.image
    }

    /** 修改时间缺失时按有效期缓存；有修改时间时由缓存键判断是否过期。 */
    private fun cacheMaxAge(file: MediaFile): Long? = thumbnailCacheMaxAge(file, policy)

    /**
     * 先查缓存；没有就让第一个调用者抽取，同一缓存键的并发调用等待同一结果。
     * 命中缓存时的时长读取也由第一个调用者完成，并发调用共享结果。
     * 首个调用者被取消时，等待者重新竞争，避免把取消当成抽取失败。
     */
    private suspend fun cachedOrLoad(
        key: String,
        maxAgeMillis: Long?,
        onCacheHit: suspend (CachedThumbnail) -> LoadedPreview,
        load: suspend () -> LoadedPreview,
    ): LoadedPreview {
        while (true) {
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
                    val cached = cache.entry(key, maxAgeMillis)
                    if (cached != null) {
                        // 已有缓存时抽取失败记录已陈旧，清掉。
                        extractionFailures.remove(key)
                        onCacheHit(cached)
                    } else {
                        loadWithBackoff(key, load)
                    }
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
                // 抽取抛 Error 或其它未被捕获的异常时也要唤醒等待者，不能留下永远未完成的 Deferred。
                if (!mine.isCompleted) mine.cancel(CancellationException("预览抽取未完成"))
                inFlight.remove(key, mine)
            }
        }
    }

    /** 整次抽取失败后按键退避；退避到期才允许下一次真正重试。 */
    private suspend fun loadWithBackoff(key: String, load: suspend () -> LoadedPreview): LoadedPreview {
        val now = monotonicClock()
        val previous = extractionFailures[key]
        if (previous != null && now < previous.retryAtMillis) {
            return LoadedPreview(image = null, durationMillis = null)
        }
        val loaded = try {
            load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val attempts = (previous?.attempts ?: 0) + 1
            val failedAt = monotonicClock()
            recordRetry(
                failures = extractionFailures,
                key = key,
                state = RetryState(attempts, failedAt + extractionRetryDelay(attempts)),
                now = failedAt,
            )
            return LoadedPreview(image = null, durationMillis = null)
        }
        extractionFailures.remove(key)
        return loaded
    }

    /** 记录失败退避；记录数达到上限时先清掉已过期的，再淘汰最早到期的键。 */
    private fun <K> recordRetry(
        failures: ConcurrentHashMap<K, RetryState>,
        key: K,
        state: RetryState,
        now: Long,
    ) {
        if (failures.size >= MAX_RETRY_ENTRIES) {
            failures.entries.removeAll { it.value.retryAtMillis <= now }
            if (failures.size >= MAX_RETRY_ENTRIES) {
                failures.entries.minByOrNull { it.value.retryAtMillis }
                    ?.let { failures.remove(it.key) }
            }
        }
        failures[key] = state
    }

    /** 连续失败按倍数退避，上限为十分钟。 */
    private fun extractionRetryDelay(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 6)
        return (EXTRACTION_RETRY_BASE_MILLIS shl shift).coerceAtMost(EXTRACTION_RETRY_MAX_MILLIS)
    }

    private data class RetryState(val attempts: Int, val retryAtMillis: Long)

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

        /** 整次抽取失败后的首次重试间隔；连续失败按倍数退避。 */
        private const val EXTRACTION_RETRY_BASE_MILLIS = 30_000L

        /** 整次抽取失败后的最大重试间隔。 */
        private const val EXTRACTION_RETRY_MAX_MILLIS = 10 * 60 * 1000L

        /** 时长读取失败后的首次重试间隔；连续失败按倍数退避。 */
        private const val DURATION_RETRY_BASE_MILLIS = 30_000L

        /** 时长读取失败后的最大重试间隔。 */
        private const val DURATION_RETRY_MAX_MILLIS = 10 * 60 * 1000L

        /** 退避记录上限；超出时清理过期项，避免长期运行后无限增长。 */
        private const val MAX_RETRY_ENTRIES = 512

        fun create(context: Context, networkReads: NetworkMediaReads): CachedMediaPreviewSource {
            val appContext = context.applicationContext
            val policy = ThumbnailPolicy()
            return CachedMediaPreviewSource(
                extractor = AndroidMediaPreviewExtractor(appContext, networkReads),
                cache = thumbnailCache(appContext),
                policy = policy,
            )
        }

        /** 缩略图缓存目录的唯一入口；播放服务只读同一份缓存。 */
        internal fun thumbnailCache(context: Context): ThumbnailCache =
            ThumbnailCache(context.applicationContext.filesDir.resolve("thumbnails"), CACHE_BYTES)
    }
}

/**
 * 缩略图缓存键：位置、大小、修改时间、抽帧策略版本与目标宽度。
 * 音频封面与视频帧都按有效目标宽度缩放，因此宽度同样参与音频缓存键。
 * 修改时间缺失时用有效期代替修改时间；有效期变化必须视为另一份缓存。
 */
internal fun thumbnailCacheKey(file: MediaFile, policy: ThumbnailPolicy): String {
    val base = "${file.location}|${file.size}|v${policy.version}|w${effectiveTargetWidth(policy.targetWidth)}"
    return if (file.lastModified > 0) {
        "$base|m${file.lastModified}"
    } else {
        "$base|validity:${policy.missingModifiedValidityMillis.coerceAtLeast(1)}"
    }
}

/** 修改时间缺失的条目按有效期读取；有修改时间时不限有效期。 */
internal fun thumbnailCacheMaxAge(file: MediaFile, policy: ThumbnailPolicy): Long? =
    if (file.lastModified > 0) null else policy.missingModifiedValidityMillis.coerceAtLeast(1)
