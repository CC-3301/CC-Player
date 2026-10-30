package com.cc3301.ccplayer.library

import android.content.Context
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * 卡片预览接缝：列表界面只依赖本接口，本地与网络实现由此注入。
 */
interface MediaPreviewSource {

    /** 取卡片预览；目录没有预览。可见项请求在取抽帧许可时优先于预取。 */
    suspend fun preview(file: MediaFile): MediaPreview

    /**
     * 可见请求：`index` 为条目在列表中的索引。
     * 抽帧许可只有一个，可见请求按索引从前到后插队取得许可。
     */
    suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview = preview(file)

    /** 预取请求；先让可见项取许可，取得许可后复查仍在范围内才抽取。 */
    suspend fun prefetch(file: MediaFile): MediaPreview = preview(file)

    /**
     * 更新当前可见与预取范围。
     * 排队中的预取取得许可后据此复查：范围不再包含该位置时直接放弃，不消耗抽取。
     * 可见请求不做复查，只按优先级取得许可。
     */
    fun updatePreviewWindow(visibleLocations: Set<String>, prefetchLocations: Set<String>) = Unit
}

/**
 * 卡片预览：预览图（null 时界面用占位图）与时长（null 时不显示角标）。
 * [retryable] 表示这次是失败收尾，调度器先跳过该项，等可见项出完后按递进间隔重试。
 */
data class MediaPreview(val image: File?, val durationMillis: Long?, val retryable: Boolean = false)

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
    suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray?

    /**
     * 网络取图的准备：在取得解码许可之前打开句柄并按目标位置起预读。
     * 返回 null 表示该实现不走预读，由调用方直接调 [imageBytes]。
     */
    suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation? = null

    /** 取时长（毫秒）；元数据里没有时长时返回 null，读取失败抛出异常。 */
    suspend fun durationMillis(file: MediaFile): Long?

    /** 在同一次读取会话里取图与时长；时长读取失败不影响已解出的图片。 */
    suspend fun imageAndDuration(file: MediaFile, targetWidth: Int): MediaPreviewExtraction {
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
 * 抽帧全局串行：本地与网络共用一份容量为 1 的许可。底层取帧是阻塞的原生调用且取消不掉，
 * 同一时刻只跑一路；许可按列表索引发给排队的可见请求，许可空闲也不让后来者越过排队者。
 * 网络条目名额（只算已开好、准备交给解码的句柄）同样按索引优先给可见项，预取占不到全部名额。
 * 缓存命中立即出图，不排队、不等同键在途抽取；时长未知时在后台补读，不排到可见队列前面。
 * 同一缓存键的并发请求只抽取一次。
 * 失败或超时保持占位图且不写缓存；可见请求失败按键退避，预取失败不写退避。
 * 成功封面落盘复用；失败与无图结果只在本次运行内记忆。
 */
class CachedMediaPreviewSource(
    private val extractor: MediaPreviewExtractor,
    private val cache: ThumbnailCache,
    private val policy: ThumbnailPolicy = ThumbnailPolicy(),
    /** 单调时钟，单位毫秒；有效期与失败退避都按毫秒比较。 */
    private val monotonicClockMillis: () -> Long = { systemMonotonicMillis() },
    /** 诊断日志出口；记文件名、失败类型与网络抽帧的排队与取帧耗时，不记连接凭据。 */
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
    /** 时长补读等后台任务的执行范围；应用内由 [create] 传入进程级范围，缺省自建。 */
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MediaPreviewSource {

    /** 抽帧许可：容量为 1，本地与网络共用；可见项优先于预取取得。 */
    private val permits = PrioritySemaphore(EXTRACTION_CONCURRENCY)

    /**
     * 网络条目名额：限制同时处于「句柄已开、准备交给解码」状态的条目数，一条目占两条句柄。
     * 可见项优先取得；预取最多占用预留名额之外的部分。
     * 名额只界定已经交付的句柄：正在打开的不计入，超时归还名额后仍可能有阻塞的打开在后台收尾。
     */
    private val preparedSlots = PrioritySemaphore(
        capacity = MAX_PREPARED_PREVIEWS,
        prefetchCapacity = MAX_PREPARED_PREVIEWS - VISIBLE_RESERVED_PREPARED_SLOTS,
    )

    /** 本次运行已确认无图的缓存键；按最近使用顺序保留有限条数。 */
    private val noImage = NoImageMemory()

    /** 当前可见与预取范围；列表页滚动时更新，排队请求据此放弃离屏任务。 */
    @Volatile
    private var previewWindow = PreviewWindow()

    /** 同一缓存键正在进行的抽取；等待者共享同一次结果。 */
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    /** 命中后补读时长失败后的退避记录。 */
    private val durationFailures = ConcurrentHashMap<String, RetryState>()

    /** 正在后台补读时长的缓存键与那一次的 Job；同键只补读一次。 */
    private val durationRefills = ConcurrentHashMap<String, Job>()

    override suspend fun preview(file: MediaFile): MediaPreview =
        preview(file, PreviewPriority.visible(Int.MAX_VALUE - 1))

    override suspend fun visiblePreview(file: MediaFile, index: Int): MediaPreview =
        preview(file, PreviewPriority.visible(index))

    override suspend fun prefetch(file: MediaFile): MediaPreview =
        preview(file, PreviewPriority.Prefetch)

    /**
     * 更新当前可见与预取范围。
     * 排队中的预取取得许可后据此复查：范围不再包含该位置时直接放弃，不消耗抽取。
     */
    @Synchronized
    override fun updatePreviewWindow(visibleLocations: Set<String>, prefetchLocations: Set<String>) {
        val visible = visibleLocations.toSet()
        val prefetch = prefetchLocations.toSet()
        if (previewWindow.visible == visible && previewWindow.prefetch == prefetch) return
        previewWindow = PreviewWindow(visible = visible, prefetch = prefetch)
    }

    private suspend fun preview(file: MediaFile, priority: PreviewPriority): MediaPreview {
        if (file.isDirectory) return MediaPreview(image = null, durationMillis = null)
        val targetWidth = previewTargetWidth(file, policy)
        val key = thumbnailCacheKey(file, policy, targetWidth)
        return when (file.source) {
            MediaSource.Local -> localPreview(file, key, targetWidth, priority)
            MediaSource.Network -> networkImage(file, key, targetWidth, priority).toPreview()
        }
    }

    private suspend fun localPreview(
        file: MediaFile,
        key: String,
        targetWidth: Int,
        priority: PreviewPriority,
    ): MediaPreview {
        val cached = cache.entry(key, cacheMaxAge(file))
        if (cached != null) {
            // 上次运行留下的无图记录只用于复用时长；本次运行仍需重新确认是否有图。
            if (!cached.noImage || isNoImageRemembered(key)) {
                // 命中缓存立即出图：不排队、不等同键在途抽取，时长未知时在后台补读。
                if (cached.file != null) forgetNoImage(key)
                refillDuration(file, key, cached, priority)
                return MediaPreview(cached.file, cached.durationMillis)
            }
        } else {
            // 本地无图记录有效期到期后重新确认。
            forgetNoImage(key)
        }
        val loaded = cachedOrLoad(
            file = file,
            key = key,
            priority = priority,
            load = { extractLocal(file, key, targetWidth, priority) },
        )
        // 本地失败不进重试队列：本次运行按无图处理，重进目录再确认。
        return MediaPreview(loaded.image, loaded.durationMillis)
    }

    /** 抽取结果转成卡片预览：失败的结果带上可重试标记。 */
    private fun LoadedPreview.toPreview(): MediaPreview = MediaPreview(image, durationMillis, failed)

    /** 命中缓存后先出图；时长未知时在后台补读，排在可见与预取之后，不阻塞本次出图。 */
    private fun refillDuration(
        file: MediaFile,
        key: String,
        cached: CachedThumbnail,
        trigger: PreviewPriority,
    ) {
        if (cached.durationKnown) return
        // 后台范围已取消（进程收尾）时不再补读，也不登记。
        if (!backgroundScope.isActive) return
        // 先占位再启动：占位失败说明同键已在补读，直接放弃这一次。
        val job = backgroundScope.launch(start = CoroutineStart.LAZY) {
            when (val duration = readLocalDuration(file, key, trigger)) {
                is PreviewDuration.Known -> writeDurationSafely(key, duration.millis)
                // 确定没有时长：写入后，后续命中不再重读。
                PreviewDuration.Unavailable -> writeDurationSafely(key, null)
                // 读取失败只影响角标：不落盘，退避后重试。
                PreviewDuration.Failed, null -> Unit
            }
        }
        if (durationRefills.putIfAbsent(key, job) != null) {
            job.cancel()
            return
        }
        // 完成或被取消都按身份回滚登记：同键可能已被下一轮补读重新登记。
        job.invokeOnCompletion { finishDurationRefill(key, job) }
        job.start()
    }

    /** 只删自己登记的这一次：同键的下一轮补读已重新登记时保留它的登记。 */
    private fun finishDurationRefill(key: String, job: Job) {
        durationRefills.compute(key) { _, current -> if (current === job) null else current }
    }

    /** 一次读取会话取图与时长；无图条目也记录时长，避免下次重开文件。 */
    private suspend fun extractLocal(
        file: MediaFile,
        key: String,
        targetWidth: Int,
        priority: PreviewPriority,
    ): LoadedPreview {
        val extracted = permits.withPermit(priority) {
            if (mayExtract(file.location, priority)) {
                extractor.imageAndDuration(file, targetWidth)
            } else {
                null
            }
        } ?: return LoadedPreview(image = null, durationMillis = null, abandoned = true)
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
            rememberNoImage(key)
        } else {
            forgetNoImage(key)
        }
        return LoadedPreview(image, durationMillis)
    }

    /**
     * 读时长失败只影响角标；可见请求失败后退避，避免慢源每次命中都重试并长期占用许可。
     * 许可按最低优先级排队：补读时长不排到可见请求与预取之前；准入仍按触发它的请求判断。
     */
    private suspend fun readLocalDuration(
        file: MediaFile,
        key: String,
        priority: PreviewPriority,
    ): PreviewDuration? {
        val now = monotonicClockMillis()
        val previous = durationFailures[key]
        if (previous != null && now < previous.retryAtMillis) return PreviewDuration.Failed
        val result = permits.withPermit(PreviewPriority.Duration) {
            if (!mayExtract(file.location, priority)) {
                null
            } else {
                try {
                    when (val millis = extractor.durationMillis(file)) {
                        null -> PreviewDuration.Unavailable
                        else -> PreviewDuration.Known(millis)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    PreviewDuration.Failed
                }
            }
        } ?: return null
        if (result == PreviewDuration.Failed) {
            // 预取失败不写退避：坏文件不会因此长期占住唯一许可。
            if (priority.isVisible) {
                val attempts = (previous?.attempts ?: 0) + 1
                val failedAt = monotonicClockMillis()
                recordRetry(
                    failures = durationFailures,
                    key = key,
                    state = RetryState(attempts, failedAt + durationRetryDelay(attempts)),
                    now = failedAt,
                )
            }
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

    private suspend fun networkImage(
        file: MediaFile,
        key: String,
        targetWidth: Int,
        priority: PreviewPriority,
    ): LoadedPreview {
        // 本次运行已确认无图：不再重复抽取，重启后重新确认。
        if (isNoImageRemembered(key)) return LoadedPreview(image = null, durationMillis = null)
        val cached = cache.entry(key, cacheMaxAge(file))
        if (cached != null) {
            // 命中缓存立即出图，不等同键在途抽取；网络条目不显示时长，命中不补读。
            if (cached.file != null) forgetNoImage(key)
            return LoadedPreview(image = cached.file, durationMillis = null)
        }
        // 入队即记：没有这一行说明请求根本没进来，可与后续的取许可等待、等播放读区分。
        if (priority.isVisible) diagnostics.log { "网络封面: ${file.name} 入队" }
        val loaded = cachedOrLoad(
            file = file,
            key = key,
            priority = priority,
            load = {
                // 范围判定前置：离屏预取不再白开两条句柄、白读头尾。
                if (!mayExtract(file.location, priority)) {
                    LoadedPreview(image = null, durationMillis = null, abandoned = true)
                } else {
                    preparedSlots.withPermit(priority) { prepareAndDecode(file, key, targetWidth, priority) }
                }
            },
        )
        return loaded
    }

    /**
     * 打开句柄并起预读（占一个准备名额），再等解码许可、在许可内解码。
     * 打开与解码各自受 [NETWORK_TIMEOUT_MILLIS] 约束；打开超时不跟着阻塞的原生调用一起等，名额随即归还。
     */
    private suspend fun prepareAndDecode(
        file: MediaFile,
        key: String,
        targetWidth: Int,
        priority: PreviewPriority,
    ): LoadedPreview {
        var prepared: NetworkPreviewPreparation? = null
        try {
            prepared = openOutsideRequest(file)
            // 排队时间不计入解码超时；阻塞中的网络读由各自的传输层超时兜底。
            val requestedAt = monotonicClockMillis()
            var queuedMillis = 0L
            val attempt = permits.withPermit(priority) {
                queuedMillis = monotonicClockMillis() - requestedAt
                if (!mayExtract(file.location, priority)) {
                    null
                } else {
                    var decoded: ByteArray? = null
                    val done = withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) {
                        decoded = prepared?.decode(targetWidth) ?: extractor.imageBytes(file, targetWidth)
                        true
                    } ?: false
                    done to decoded
                }
            }
            val total = monotonicClockMillis() - requestedAt
            if (attempt == null) {
                // 已离开可见与预取范围：不抽取、不落盘、不计失败。
                return LoadedPreview(image = null, durationMillis = null, abandoned = true)
            }
            diagnostics.log { "网络封面: ${file.name} 取许可等待 ${queuedMillis}ms 解码 ${total - queuedMillis}ms" }
            // 解码阻塞期间无法中断；返回后若已取消，丢弃结果，避免离屏请求落盘。
            currentCoroutineContext().ensureActive()
            val (completed, bytes) = attempt
            if (!completed) throw IllegalStateException("网络预览超时")
            return if (bytes == null) {
                // 无图只在本次运行内记住；不写缓存，重启后重新确认。
                rememberNoImage(key)
                LoadedPreview(image = null, durationMillis = null)
            } else {
                forgetNoImage(key)
                // 网络不读取时长：缓存条目记录为时长未知，而不是“确定没有时长”。
                LoadedPreview(cache.save(key, bytes, durationKnown = false), durationMillis = null)
            }
        } finally {
            // 句柄无论解码成功、失败还是取消都要关。
            prepared?.close()
        }
    }

    /**
     * 打开句柄并按目标位置起预读。
     *
     * 打开是取消不掉的阻塞原生调用：放在脱离请求的协程里跑，超时只停止等待，
     * 名额随即归还，后面的可见项不跟着一个坏条目一起等。
     * 打开最终结束后仍无人接手（超时或取消）时由打开方关闭句柄与预读。
     */
    private suspend fun openOutsideRequest(file: MediaFile): NetworkPreviewPreparation? {
        val opened = CompletableDeferred<Result<NetworkPreviewPreparation?>>()
        // 父 Job 跟随进程级范围，调度器沿用调用方的：请求取消不会连带取消这次打开。
        val opening = CoroutineScope(
            currentCoroutineContext() + Job(backgroundScope.coroutineContext[Job]),
        ).launch {
            val result = try {
                Result.success(extractor.prepareNetworkPreview(file))
            } catch (cancellation: CancellationException) {
                opened.completeExceptionally(cancellation)
                return@launch
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            // 没人接手（超时或取消）时在这里关闭，句柄不留在无人持有的地方。
            if (!opened.complete(result)) runCatching { result.getOrNull()?.close() }
        }
        val settled = try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) { opened.await() }
        } catch (failure: Throwable) {
            abandonOpen(opened, opening, CancellationException("网络预览打开已放弃"))
            throw failure
        }
        if (settled == null) {
            abandonOpen(opened, opening, CancellationException("网络预览打开超时"))
            throw IllegalStateException("网络预览打开超时")
        }
        return settled.getOrThrow()
    }

    /**
     * 放弃等待这次打开。
     *
     * 先用 [CompletableDeferred.completeExceptionally] 争这次交接的收尾权：
     * 争到（返回 true）说明子方还没交付，它随后的 complete 会返回 false 并自己关句柄；
     * 争不到（返回 false）说明子方已经交付，这一次成功打开的结果由父方接手关闭，不能丢。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun abandonOpen(
        opened: CompletableDeferred<Result<NetworkPreviewPreparation?>>,
        opening: Job,
        reason: CancellationException,
    ) {
        if (!opened.completeExceptionally(reason)) {
            runCatching { opened.getCompleted().getOrNull()?.close() }
        }
        opening.cancel()
    }

    /**
     * 排队请求取得许可后的复查：只有预取需要复查，且以当前位置是否仍在可见或预取范围为准。
     * 可见请求不复查：卡片被放弃后不会以相同键重试，会永久停在占位图。
     */
    private fun mayExtract(location: String, priority: PreviewPriority): Boolean =
        priority.isVisible || previewWindow.contains(location)

    /** 修改时间缺失时按有效期缓存；有修改时间时由缓存键判断是否过期。 */
    private fun cacheMaxAge(file: MediaFile): Long? = thumbnailCacheMaxAge(file, policy)

    /**
     * 让第一个调用者抽取，同一缓存键的并发调用等待同一结果；缓存命中已在调用方先行返回。
     * 首个调用者被取消时，等待者重新竞争，避免把取消当成抽取失败。
     */
    private suspend fun cachedOrLoad(
        file: MediaFile,
        key: String,
        priority: PreviewPriority,
        load: suspend () -> LoadedPreview,
    ): LoadedPreview {
        while (true) {
            val pending = inFlight[key]
            if (pending != null) {
                if (priority.isVisible && pending.prefetch) {
                    // 同键可见请求不等慢预取：摘掉预取并取消它，自己按可见优先级重抽。
                    inFlight.remove(key, pending)
                    pending.owner?.cancel(CancellationException("可见请求优先"))
                    yield()
                    continue
                }
                try {
                    return pending.deferred.await()
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // 首个调用者已取消且未完成，重新竞争抽取。
                    yield()
                }
            }
            val mine = CompletableDeferred<LoadedPreview>()
            val entry = InFlight(
                deferred = mine,
                prefetch = !priority.isVisible,
                owner = currentCoroutineContext()[Job],
            )
            if (inFlight.putIfAbsent(key, entry) != null) continue
            try {
                val loaded = try {
                    loadOnce(file, load)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    LoadedPreview(image = null, durationMillis = null, failed = true)
                }
                if (loaded.abandoned) {
                    // 先移出再取消：等待者不会取到已取消的 Deferred 空转。
                    inFlight.remove(key, entry)
                    mine.cancel(AbandonedPreviewException())
                    // 放弃的结果只给本次调用者；等待者重新竞争后可真正抽取或补读时长。
                    return loaded
                }
                mine.complete(loaded)
                return loaded
            } catch (cancellation: CancellationException) {
                mine.cancel(cancellation)
                throw cancellation
            } finally {
                // 抽取抛 Error 或其它未被捕获的异常时也要唤醒等待者，不能留下永远未完成的 Deferred。
                if (!mine.isCompleted) mine.cancel(CancellationException("预览抽取未完成"))
                inFlight.remove(key, entry)
            }
        }
    }

    private fun isNoImageRemembered(key: String): Boolean = noImage.contains(key)

    private fun rememberNoImage(key: String) {
        noImage.remember(key)
    }

    private fun forgetNoImage(key: String) {
        noImage.forget(key)
    }

    /**
     * 抽取一次：失败不落缓存、只标记可重试，重试节奏与次数由可见请求调度器安排。
     * 预取失败同样标记可重试，预取本身不重试，等可见请求到达时再抽。
     */
    private suspend fun loadOnce(
        file: MediaFile,
        load: suspend () -> LoadedPreview,
    ): LoadedPreview = try {
        load()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        diagnostics.log { "缩略图抽取失败: ${file.name} (${failure.javaClass.simpleName})" }
        LoadedPreview(image = null, durationMillis = null, failed = true)
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

    private data class RetryState(val attempts: Int, val retryAtMillis: Long)

    private data class LoadedPreview(
        val image: File?,
        val durationMillis: Long?,
        /** 本次结果只给本次调用者，不作为成功结果共享给同键等待者（离屏放弃或时长补读放弃）。 */
        val abandoned: Boolean = false,
        /** 这一次是失败收尾：不落缓存，交给调度器递进重试。 */
        val failed: Boolean = false,
    )

    /** 同键在途请求：等待者共享同一次抽取结果；可见请求可取消预取 owner。 */
    private data class InFlight(
        val deferred: CompletableDeferred<LoadedPreview>,
        val prefetch: Boolean,
        val owner: Job?,
    )

    companion object {
        /** 缩略图缓存目录的上限。 */
        private const val CACHE_BYTES = 192L * 1024 * 1024

        /** 抽帧许可容量：本地与网络共用同一个，同一时刻只有一路在取帧。 */
        private const val EXTRACTION_CONCURRENCY = 1

        /**
         * 网络取图的打开与解码各自的等待上限；取许可的排队时间不计入。
         * 打开超时只放弃等待并归还名额，阻塞中的原生打开留在后台，由传输层超时收尾。
         */
        private const val NETWORK_TIMEOUT_MILLIS = 15_000L

        /**
         * 同时处于「句柄已开、准备交给解码」状态的网络条目上限；一条目占两条句柄。
         * 正在打开的不计入；超时归还名额后，后台可能还留着没返回的打开。
         */
        private const val MAX_PREPARED_PREVIEWS = 4

        /** 预取不得占用的名额：预取占满其余名额时，可见项仍有这么多格可以直接打开。 */
        private const val VISIBLE_RESERVED_PREPARED_SLOTS = 2

        /** 时长读取失败后的首次重试间隔；连续失败按倍数退避。 */
        private const val DURATION_RETRY_BASE_MILLIS = 30_000L

        /** 时长读取失败后的最大重试间隔。 */
        private const val DURATION_RETRY_MAX_MILLIS = 10 * 60 * 1000L

        /** 退避记录上限；超出时清理过期项，避免长期运行后无限增长。 */
        private const val MAX_RETRY_ENTRIES = 512

        /** [backgroundScope] 由调用方给出应用级范围：时长补读等后台任务跟随进程，不随界面重建。 */
        fun create(
            context: Context,
            networkReads: NetworkMediaReads,
            diagnostics: DiagnosticsLog = NoDiagnosticsLog,
            backgroundScope: CoroutineScope,
        ): CachedMediaPreviewSource {
            val appContext = context.applicationContext
            val policy = ThumbnailPolicy()
            return CachedMediaPreviewSource(
                extractor = AndroidMediaPreviewExtractor(appContext, networkReads, diagnostics),
                cache = thumbnailCache(appContext),
                policy = policy,
                diagnostics = diagnostics,
                backgroundScope = backgroundScope,
            )
        }

        /** 缩略图缓存目录的唯一入口；播放服务只读同一份缓存。 */
        internal fun thumbnailCache(context: Context): ThumbnailCache =
            ThumbnailCache(context.applicationContext.filesDir.resolve("thumbnails"), CACHE_BYTES)
    }
}

/**
 * 缩略图缓存键：位置、大小、修改时间、抽帧策略版本与目标宽度。
 * 音频封面与视频帧都按实际目标宽度缩放，因此宽度同样参与音频缓存键。
 * 修改时间缺失时用有效期代替修改时间；有效期变化必须视为另一份缓存。
 */
internal fun thumbnailCacheKey(
    file: MediaFile,
    policy: ThumbnailPolicy,
    targetWidth: Int,
): String {
    val base = "${file.location}|${file.size}|v${policy.version}|w$targetWidth"
    return if (file.lastModified > 0) {
        "$base|m${file.lastModified}"
    } else {
        "$base|validity:${policy.missingModifiedValidityMillis.coerceAtLeast(1)}"
    }
}

/** 生产环境使用的单调时钟，单位为毫秒。 */
internal fun systemMonotonicMillis(): Long = System.nanoTime() / 1_000_000

/** 修改时间缺失的条目按有效期读取；有修改时间时不限有效期。 */
internal fun thumbnailCacheMaxAge(file: MediaFile, policy: ThumbnailPolicy): Long? =
    if (file.lastModified > 0) null else policy.missingModifiedValidityMillis.coerceAtLeast(1)

/** 当前可见与预取范围快照。 */
private data class PreviewWindow(
    val visible: Set<String> = emptySet(),
    val prefetch: Set<String> = emptySet(),
) {
    fun contains(location: String): Boolean = location in visible || location in prefetch
}

/** 预取已离开可见范围；等待同键结果的请求据此重新竞争，而不是接收空结果。 */
private class AbandonedPreviewException : CancellationException("预览请求已离开可见范围")

/**
 * 取抽帧许可的优先级：可见项先于预取，时长补读排在最后。
 * [order] 为可见项在列表中的索引；同级可见请求按索引从小到大取得许可。
 */
private data class PreviewPriority(val kind: Kind, val order: Int) {

    enum class Kind { Visible, Prefetch, Duration }

    val isVisible: Boolean get() = kind == Kind.Visible

    companion object {
        fun visible(order: Int) = PreviewPriority(Kind.Visible, order)

        val Prefetch = PreviewPriority(Kind.Prefetch, Int.MAX_VALUE)

        /** 命中缓存后补读时长：排在任何可见请求与预取之后。 */
        val Duration = PreviewPriority(Kind.Duration, Int.MAX_VALUE)
    }
}

/**
 * 带优先级的许可：许可只发给排队者中优先级最高的一位，可见请求按列表索引从小到大。
 * 许可空闲也不直接放行后来的请求，后到的高索引项不会越过仍在排队的低索引项。
 * 预取与时长补读另有配额，最多占 [prefetchCapacity] 个，可见项不会被它们堵在门外。
 * 取消等待会退出队列；已发出的许可在取消竞争中归还，避免许可泄漏。
 */
private class PrioritySemaphore(
    private val capacity: Int,
    /** 非可见请求（预取与时长补读）最多同时占用的许可数；余下的留给可见项。 */
    private val prefetchCapacity: Int = capacity,
) {

    private val lock = Mutex()
    private var available = capacity
    private val waiters = ArrayDeque<Waiter>()

    suspend fun <T> withPermit(priority: PreviewPriority, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    private suspend fun acquire(priority: PreviewPriority) {
        val waiter = Waiter(priority, CompletableDeferred())
        try {
            // 一律先入队再发放；这里的 yield 只是给同一轮下发的请求一个入队机会，
            // 不构成「同轮请求必全部入队」的保证（用立即调度时不让出）。
            // 顺序由 grantIdleLocked 每次从整队取最小 rank 决定，加上下发按索引升序 start。
            lock.withLock { waiters.addLast(waiter) }
            yield()
            lock.withLock { grantIdleLocked() }
            waiter.granted.await()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                // 仍在排队：直接退出；许可已发出：交还，让排在后面的请求继续。
                if (!lock.withLock { waiters.remove(waiter) }) release()
            }
            throw cancellation
        }
    }

    /** 连续把空闲许可发给优先级最高、且没超出自身配额的一位。 */
    private fun grantIdleLocked() {
        while (available > 0) {
            val index = waiters.indices
                .filter { position -> withinQuotaLocked(waiters[position]) }
                .minByOrNull { position -> priorityRank(waiters[position]) } ?: return
            val granted = waiters.removeAt(index)
            available--
            granted.granted.complete(Unit)
        }
    }

    /** 预取与时长补读最多同时占用 [prefetchCapacity] 个；可见项不受配额限制。 */
    private fun withinQuotaLocked(waiter: Waiter): Boolean {
        if (waiter.priority.isVisible) return true
        return capacity - available < prefetchCapacity
    }

    private suspend fun release() {
        val next = lock.withLock {
            available++
            val index = waiters.indices
                .filter { position -> withinQuotaLocked(waiters[position]) }
                .minByOrNull { position -> priorityRank(waiters[position]) }
            if (index == null) {
                null
            } else {
                waiters.removeAt(index).also { available-- }
            }
        }
        next?.granted?.complete(Unit)
    }

    /** 可见请求先于预取，时长补读最后；可见请求内按列表索引排序，同级保持等待顺序。 */
    private fun priorityRank(waiter: Waiter): Long = when (waiter.priority.kind) {
        PreviewPriority.Kind.Visible -> waiter.priority.order.toLong()
        PreviewPriority.Kind.Prefetch -> PREFETCH_RANK
        PreviewPriority.Kind.Duration -> DURATION_RANK
    }

    private class Waiter(
        val priority: PreviewPriority,
        val granted: CompletableDeferred<Unit>,
    )

    private companion object {
        const val PREFETCH_RANK = Long.MAX_VALUE - 1
        const val DURATION_RANK = Long.MAX_VALUE
    }
}
