package com.cc3301.ccplayer.library

import android.content.Context
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import java.io.File
import java.io.IOException
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

    /**
     * 只读缓存取封面：不触发抽取、不排队，也没有时不落「无图」。
     * 播放页封面层用它与列表共用同一份缓存；没有缓存或已超过有效期时返回 null。
     */
    suspend fun cachedImage(file: MediaFile): File? = null
}

/**
 * 卡片预览：预览图（null 时界面用占位图）与时长（null 时不显示角标）。
 * [retryable] 表示这次是失败收尾，调度器先跳过该项，等可见项出完后按递进间隔重试。
 * [attempts] 是抽帧状态里的已试次数；调度器据它算重试间隔与次数，不再自己计数。
 */
data class MediaPreview(
    val image: File?,
    val durationMillis: Long?,
    val retryable: Boolean = false,
    val attempts: Int = 0,
)

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

/** 抽帧失败的阶段；日志按阶段各写一类，便于判断卡在哪一环。 */
enum class PreviewFailureStage {
    /** 打开数据源失败。 */
    OpenSource,

    /** 读取字节失败。 */
    Read,

    /** 解码帧或封面失败。 */
    Decode,

    /** 等读取超时。 */
    Timeout,
}

/** 带阶段的抽取失败；上层按 [stage] 记日志，重试节奏仍由调度器安排。 */
class PreviewStageException(
    val stage: PreviewFailureStage,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 失败阶段的名字；日志与抽帧状态共用这一套叫法。 */
internal val PreviewFailureStage.label: String
    get() = when (this) {
        PreviewFailureStage.OpenSource -> "打开数据源"
        PreviewFailureStage.Read -> "读取"
        PreviewFailureStage.Decode -> "解码"
        PreviewFailureStage.Timeout -> "超时"
    }

/** 抽取失败落在哪一阶段；日志里的阶段名只有这一处映射。 */
internal fun previewFailureStage(failure: Exception): String = when (failure) {
    is PreviewStageException -> failure.stage.label

    is MediaSourceException -> if (failure.failure == MediaFailure.Unreachable) "建连" else "打开数据源"
    is java.io.FileNotFoundException -> "打开数据源"
    is IllegalArgumentException, is SecurityException -> "路径解析"
    else -> "读取"
}

/**
 * 从媒体文件里取预览图与时长；实现依赖 Android 解码器，测试用假实现替换。
 */
interface MediaPreviewExtractor {

    /**
     * 取预览图字节：视频按策略抽帧，音频是文件内嵌封面；没有可用图时返回 null，读取失败抛出异常。
     * 只用于本地文件；网络条目只走 [prepareNetworkPreview]。
     */
    suspend fun imageBytes(file: MediaFile, targetWidth: Int): ByteArray?

    /**
     * 网络取图的准备：在取得解码许可之前打开句柄并按目标位置起预读。
     * 返回 null 表示没有可用的读取会话，调用方按无图处理，不再另开句柄取图。
     */
    suspend fun prepareNetworkPreview(file: MediaFile): NetworkPreviewPreparation?

    /**
     * 带预读 rank 的同一件事：[readAheadRank] 决定这条目的预读在池里的先后，
     * 可见项按列表索引，预取排在可见之后。默认忽略 rank，实现只覆盖一参版本。
     */
    suspend fun prepareNetworkPreview(
        file: MediaFile,
        readAheadRank: Long,
    ): NetworkPreviewPreparation? = prepareNetworkPreview(file)

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
 * 抽帧全局串行：本地与网络共用一份容量为 1 的许可。视频抽帧由 Media3 执行（组件内部也串行）；
 * 超时或取消只停止等待、不取消在途任务，同一时刻只跑一路；许可按列表索引发给排队的可见请求，
 * 许可空闲也不让后来者越过排队者。
 * 网络读取会话名额（从开始打开占到解码结束）同样按索引优先给可见项，预取占不到全部名额；
 * 名额只限制同时打开的网络读取会话数，解码仍由唯一许可串行执行、一次一张。
 * 缓存命中立即出图，不排队、不等同键在途抽取；时长未知时在后台补读，不排到可见队列前面。
 * 同一缓存键的并发请求只抽取一次。
 * 上次结果、已试次数、无图记忆、预取抑制与时长退避都在同一份抽帧状态里；
 * 可见请求调度器读同一份决定失败重试的间隔与次数。
 * 失败或超时保持占位图且不写缓存；可见请求的失败交给调度器按递进间隔重试：
 * 本地最多重试 3 次，网络用尽调度器的次数后条目再次可见会重新给一轮。
 * 只有解码成功但没有图才写「无图」缓存；抽取失败不写「无图」。
 * 网络预取失败不在后台重试，也不记无图，只抑制后续预取；回到可见后清掉抑制并重新抽取。
 * 成功封面落盘复用；网络路径只有可见请求确认的无图才在本次运行内记忆，预取不记无图；
 * 「确认无图」的进程内记忆 10 分钟后过期，过期后重新确认。
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
     * 网络读取会话名额：限制同时处于「开始打开到解码结束」状态的条目数，一条目占两条句柄。
     * 这里限制的是同时打开中（含正在打开）的网络读取会话数，不是同时解码数：
     * 解码仍由 [permits] 串行执行、一次一张。
     * 可见项优先取得；预取最多占用预留名额之外的部分。
     * 名额从开始打开占到解码结束；超时归还名额后，后台仍可能留着未返回的打开，重试会再开一份。
     */
    private val preparedSlots = PrioritySemaphore(
        capacity = MAX_PREPARED_PREVIEWS,
        prefetchCapacity = MAX_PREPARED_PREVIEWS - VISIBLE_RESERVED_PREPARED_SLOTS,
    )

    /**
     * 本次运行的抽帧状态：一个条目一份，上次结果、已试次数、退避与预取抑制都在这里。
     * 无图记忆的有效期按进程内真实时钟计时，与缓存有效期用的注入时钟分开。
     */
    private val entries = PreviewEntryStates()

    /** 当前可见与预取范围；列表页滚动时更新，排队请求据此放弃离屏任务。 */
    @Volatile
    private var previewWindow = PreviewWindow()

    /** 同一缓存键正在进行的抽取；等待者共享同一次结果。 */
    private val inFlight = ConcurrentHashMap<String, InFlight>()

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
        if (priority.isVisible) {
            // 可见请求命中：先清掉预取抑制，回到这一屏仍要重新准备与解码。
            clearPrefetchSuppression(key)
        } else if (isPrefetchSuppressed(key)) {
            // 本次运行内预取已失败或无图：不再重复打开会话与解码，等可见请求再试。
            return MediaPreview(image = null, durationMillis = null)
        }
        return when (file.source) {
            MediaSource.Local -> localPreview(file, key, targetWidth, priority)
            MediaSource.Network -> networkImage(file, key, targetWidth, priority).toPreview(key)
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
        if (loaded.abandoned) return MediaPreview(image = null, durationMillis = null)
        val stage = loaded.failureStage
        if (stage == null) return MediaPreview(loaded.image, loaded.durationMillis)
        // 本地抽取失败先按失败收尾交给调度器重试，次数用尽后保持占位图。
        val attempts = stateFor(key).fail(stage)
        return MediaPreview(
            image = loaded.image,
            durationMillis = loaded.durationMillis,
            retryable = attempts <= LOCAL_RETRY_LIMIT,
            attempts = attempts,
        )
    }

    /** 只读缓存取封面：播放页封面层与列表共用同一份缓存，不触发抽取。 */
    override suspend fun cachedImage(file: MediaFile): File? {
        if (file.isDirectory) return null
        val targetWidth = previewTargetWidth(file, policy)
        return cache.entry(thumbnailCacheKey(file, policy, targetWidth), cacheMaxAge(file))?.file
    }

    /** 抽取结果转成卡片预览：失败的结果带上可重试标记与状态里的已试次数。 */
    private fun LoadedPreview.toPreview(key: String): MediaPreview = MediaPreview(
        image = image,
        durationMillis = durationMillis,
        retryable = failed,
        attempts = entryState(key)?.attempts ?: 0,
    )

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
        // 等解码许可有上限：前一项卡住时不能无限期停在这一段。
        val requestedAt = monotonicClockMillis()
        val acquired = permits.acquireOrNull(priority, DECODE_PERMIT_TIMEOUT_MILLIS)
        val waited = monotonicClockMillis() - requestedAt
        if (!acquired) {
            diagnostics.log { "本地封面: ${file.name} 等解码许可超时 ${waited}ms" }
            return LoadedPreview(
                image = null,
                durationMillis = null,
                failureStage = PreviewFailureStage.Timeout.label,
            )
        }
        diagnostics.log { "本地封面: ${file.name} 等解码许可 ${waited}ms" }
        try {
            if (!mayExtract(file.location, priority)) {
                return LoadedPreview(image = null, durationMillis = null, abandoned = true)
            }
            diagnostics.log { "本地封面: ${file.name} 开始抽帧" }
            val started = monotonicClockMillis()
            val extracted = try {
                extractor.imageAndDuration(file, targetWidth)
            } finally {
                diagnostics.log { "本地封面: ${file.name} 抽帧完成 ${monotonicClockMillis() - started}ms" }
            }
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
                // 只有解码成功但没有图才写无图缓存；抽取失败不写。
                diagnostics.log { "封面无图: ${file.name}" }
                // 无图条目只记时长；时长读取失败时记“未知”，下次命中再补读。
                cache.saveWithoutImage(
                    location = key,
                    durationMillis = durationMillis,
                    durationKnown = durationKnown,
                )
                rememberNoImage(key)
            } else {
                stateFor(key).succeed()
            }
            return LoadedPreview(image, durationMillis)
        } finally {
            permits.release()
        }
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
        val recorded = entryState(key)
        if (recorded != null && now < recorded.retryAtMillis) return PreviewDuration.Failed
        if (!permits.acquireOrNull(PreviewPriority.Duration, null)) return null
        val result = (try {
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
        } finally {
            permits.release()
        }) ?: return null
        if (result == PreviewDuration.Failed) {
            // 预取失败不写退避：坏文件不会因此长期占住唯一许可。
            if (priority.isVisible) {
                val state = stateFor(key)
                val attempts = state.failDuration()
                state.deferUntil(monotonicClockMillis() + durationRetryDelay(attempts))
            }
        } else {
            entryState(key)?.clearDurationFailure()
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
                    val started = monotonicClockMillis()
                    val acquired = preparedSlots.acquireOrNull(priority, PREPARED_SLOT_TIMEOUT_MILLIS)
                    val waited = monotonicClockMillis() - started
                    if (!acquired) {
                        diagnostics.log { "网络封面: ${file.name} 等预打开名额超时 ${waited}ms" }
                        LoadedPreview(
                            image = null,
                            durationMillis = null,
                            failureStage = PreviewFailureStage.Timeout.label,
                        )
                    } else {
                        try {
                            diagnostics.log { "网络封面: ${file.name} 等预打开名额 ${waited}ms" }
                            prepareAndDecode(file, key, targetWidth, priority)
                        } finally {
                            preparedSlots.release()
                        }
                    }
                }
            },
        )
        // 失败次数记进抽帧状态：调度器读同一份决定重试间隔与次数。
        val stage = loaded.failureStage
        if (stage != null) stateFor(key).fail(stage)
        notePrefetchOutcome(key, priority, loaded)
        return loaded
    }

    /** 预取失败或无图时记下；放弃与取消不记，出图时由状态一起清掉。 */
    private fun notePrefetchOutcome(key: String, priority: PreviewPriority, loaded: LoadedPreview) {
        if (priority.isVisible || loaded.abandoned) return
        if (loaded.failed || loaded.image == null) stateFor(key).suppressPrefetch()
    }

    /**
     * 打开句柄并起预读（占一个网络读取会话名额），再等解码许可、在许可内串行解码。
     * 打开与解码各自受 [NETWORK_TIMEOUT_MILLIS] 约束；打开超时不跟着阻塞的原生调用一起等，名额随即归还。
     */
    private suspend fun prepareAndDecode(
        file: MediaFile,
        key: String,
        targetWidth: Int,
        priority: PreviewPriority,
    ): LoadedPreview {
        // 打开数据源有上限：打开是取消不掉的阻塞调用，超时只停止等待，名额随即归还。
        val openStarted = monotonicClockMillis()
        val prepared = (try {
            openOutsideRequest(file, priority)
        } finally {
            diagnostics.log { "网络封面: ${file.name} 打开数据源 ${monotonicClockMillis() - openStarted}ms" }
        }) ?: return LoadedPreview(image = null, durationMillis = null)
        var abandoned = false
        try {
            // 等解码许可有上限：许可被前一项占着时，不能无限期停在这一段。
            val requestedAt = monotonicClockMillis()
            val acquired = permits.acquireOrNull(priority, DECODE_PERMIT_TIMEOUT_MILLIS)
            val queuedMillis = monotonicClockMillis() - requestedAt
            if (!acquired) {
                diagnostics.log { "网络封面: ${file.name} 等解码许可超时 ${queuedMillis}ms" }
                throw PreviewStageException(PreviewFailureStage.Timeout, "等解码许可超时")
            }
            diagnostics.log { "网络封面: ${file.name} 等解码许可 ${queuedMillis}ms" }
            val attempt = try {
                if (!mayExtract(file.location, priority)) {
                    null
                } else {
                    diagnostics.log { "网络封面: ${file.name} 开始抽帧" }
                    var decoded: ByteArray? = null
                    val decodeStarted = monotonicClockMillis()
                    val done = try {
                        withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) {
                            decoded = prepared.decode(targetWidth)
                            true
                        } ?: false
                    } finally {
                        // 取消路径也要留下完成耗时，才能和开始行成对。
                        diagnostics.log {
                            "网络封面: ${file.name} 抽帧完成 ${monotonicClockMillis() - decodeStarted}ms"
                        }
                    }
                    done to decoded
                }
            } finally {
                permits.release()
            }
            if (attempt == null) {
                // 已离开可见与预取范围：不抽取、不落盘、不计失败。
                return LoadedPreview(image = null, durationMillis = null, abandoned = true)
            }
            // 解码阻塞期间无法中断；返回后若已取消，丢弃结果，避免离屏请求落盘。
            currentCoroutineContext().ensureActive()
            val (completed, bytes) = attempt
            if (!completed) {
                // 超时走放弃收尾：打断了在途传输读才作废会话，下一次重试重新建连。
                abandoned = true
                throw PreviewStageException(PreviewFailureStage.Timeout, "网络预览超时")
            }
            return if (bytes == null) {
                // 只有可见请求确认的无图才记住；预取结果不记，回到可见时还要再抽一次。
                // 不写缓存，重启后重新确认。
                if (priority.isVisible) {
                    diagnostics.log { "封面无图: ${file.name}" }
                    rememberNoImage(key)
                }
                LoadedPreview(image = null, durationMillis = null)
            } else {
                stateFor(key).succeed()
                // 网络不读取时长：缓存条目记录为时长未知，而不是“确定没有时长”。
                LoadedPreview(cache.save(key, bytes, durationKnown = false), durationMillis = null)
            }
        } catch (cancellation: CancellationException) {
            // 取消同样走放弃收尾：只有打断在途传输读才作废会话，排队或已读完的取消保留会话。
            abandoned = true
            throw cancellation
        } finally {
            // 句柄无论解码成功、失败还是取消都要关；解码超时或取消按是否打断传输读决定是否丢弃会话。
            if (abandoned) prepared.abandon() else prepared.close()
        }
    }

    /**
     * 打开句柄并按目标位置起预读。
     *
     * 打开是取消不掉的阻塞原生调用：放在脱离请求的协程里跑，超时只停止等待，
     * 名额随即归还，后面的可见项不跟着一个坏条目一起等。
     * 打开最终结束后仍无人接手（超时或取消）时由打开方关闭句柄与预读。
     */
    private suspend fun openOutsideRequest(
        file: MediaFile,
        priority: PreviewPriority,
    ): NetworkPreviewPreparation? {
        val opened = CompletableDeferred<Result<NetworkPreviewPreparation?>>()
        // 父 Job 跟随进程级范围，调度器沿用调用方的：请求取消不会连带取消这次打开。
        val opening = CoroutineScope(
            currentCoroutineContext() + Job(backgroundScope.coroutineContext[Job]),
        ).launch {
            val result = try {
                Result.success(extractor.prepareNetworkPreview(file, priority.rank))
            } catch (cancellation: CancellationException) {
                opened.completeExceptionally(cancellation)
                return@launch
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            // 没人接手（超时或取消）时在这里作废，句柄不留在无人持有的地方，会话也不留给下一次重试。
            if (!opened.complete(result)) runCatching { result.getOrNull()?.discard() }
        }
        val settled = try {
            withTimeoutOrNull(NETWORK_TIMEOUT_MILLIS) { opened.await() }
        } catch (failure: Throwable) {
            abandonOpen(opened, opening, CancellationException("网络预览打开已放弃"))
            throw failure
        }
        if (settled == null) {
            abandonOpen(opened, opening, CancellationException("网络预览打开超时"))
            throw PreviewStageException(PreviewFailureStage.Timeout, "网络预览打开超时")
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
    private suspend fun abandonOpen(
        opened: CompletableDeferred<Result<NetworkPreviewPreparation?>>,
        opening: Job,
        reason: CancellationException,
    ) {
        if (!opened.completeExceptionally(reason)) {
            // 争到的是已经交付的成功打开：打开已被放弃，会话一并作废，下一次重试换新会话。
            runCatching { opened.getCompleted().getOrNull()?.discard() }
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
                    LoadedPreview(
                        image = null,
                        durationMillis = null,
                        failureStage = previewFailureStage(failure),
                    )
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

    /** 条目本次运行的抽帧状态；没有记录时返回 null。 */
    private fun entryState(key: String): PreviewEntryState? = entries.state(key)

    /** 取条目本次运行的抽帧状态，没有记录时新建。 */
    private fun stateFor(key: String): PreviewEntryState = entries.stateFor(key)

    private fun isNoImageRemembered(key: String): Boolean = entries.isNoImageRemembered(key)

    private fun rememberNoImage(key: String) {
        entries.rememberNoImage(key)
    }

    private fun forgetNoImage(key: String) {
        entryState(key)?.forgetNoImage()
    }

    private fun isPrefetchSuppressed(key: String): Boolean = entryState(key)?.prefetchSuppressed == true

    private fun clearPrefetchSuppression(key: String) {
        entryState(key)?.clearPrefetchSuppression()
    }

    /**
     * 抽取一次：失败不落缓存、只标记可重试，重试节奏与次数由可见请求调度器安排。
     * 预取失败不在后台重试，也不记成无图；等该项回到可见范围时再按可见请求抽取。
     */
    private suspend fun loadOnce(
        file: MediaFile,
        load: suspend () -> LoadedPreview,
    ): LoadedPreview = try {
        load()
    } catch (cancellation: CancellationException) {
        diagnostics.log { "缩略图取消: ${file.name} | 原因: ${cancellation.cancelReason()}" }
        throw cancellation
    } catch (failure: Exception) {
        val stage = previewFailureStage(failure)
        // 只记类名：异常消息可能含路径与服务器信息，不进日志。
        val cause = failure.causeClassNameOrNull()
        diagnostics.log {
            "缩略图抽取失败: ${file.name} (${failure.javaClass.simpleName}) | 阶段: $stage" +
                (cause?.let { " | 原因: $it" } ?: "")
        }
        LoadedPreview(image = null, durationMillis = null, failureStage = stage)
    }

    private class LoadedPreview(
        val image: File?,
        val durationMillis: Long?,
        /** 本次结果只给本次调用者，不作为成功结果共享给同键等待者（离屏放弃或时长补读放弃）。 */
        val abandoned: Boolean = false,
        /** 失败阶段；null 表示不是失败收尾，落到缓存或占位图。 */
        val failureStage: String? = null,
    ) {
        /** 这一次是失败收尾：不落缓存，交给调度器递进重试。 */
        val failed: Boolean get() = failureStage != null
    }

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

        /** 等预打开名额的上限；名额被占满时不能无限期排队。 */
        private const val PREPARED_SLOT_TIMEOUT_MILLIS = 20_000L

        /** 等解码许可的上限；许可被前一项占着时不能无限期排队。 */
        private const val DECODE_PERMIT_TIMEOUT_MILLIS = 20_000L

        /**
         * 同时处于「开始打开到解码结束」状态的网络条目上限；一条目占两条句柄。
         * 只限制同时打开中（含正在打开）的读取会话数，不改变解码并发：解码仍全局串行、一次一张。
         * 名额从开始打开占到解码结束；超时归还名额后，后台可能仍留着未返回的打开，重试会再开一份。
         */
        private const val MAX_PREPARED_PREVIEWS = 4

        /** 预取不得占用的网络读取会话名额：预取占满其余名额时，可见项仍有这么多格可以直接打开。 */
        private const val VISIBLE_RESERVED_PREPARED_SLOTS = 2

        /** 时长读取失败后的首次重试间隔；连续失败按倍数退避。 */
        private const val DURATION_RETRY_BASE_MILLIS = 30_000L

        /** 时长读取失败后的最大重试间隔。 */
        private const val DURATION_RETRY_MAX_MILLIS = 10 * 60 * 1000L

        /** 本地抽取失败后的重试次数上限；用尽后保持占位图，条目再次可见也不再重试。 */
        private const val LOCAL_RETRY_LIMIT = 3

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

    /** 取许可与派发预读用同一套 rank：可见项按列表索引，预取与时长补读排在最后。 */
    val rank: Long
        get() = when (kind) {
            Kind.Visible -> order.toLong()
            Kind.Prefetch -> PREVIEW_PREFETCH_RANK
            Kind.Duration -> PREVIEW_DURATION_RANK
        }

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

    /** 取得许可；[timeoutMillis] 非空时等待有上限，超时返回 false。取消会退出队列。 */
    suspend fun acquireOrNull(priority: PreviewPriority, timeoutMillis: Long?): Boolean {
        if (timeoutMillis == null) {
            acquire(priority)
            return true
        }
        return withTimeoutOrNull(timeoutMillis) {
            acquire(priority)
            true
        } ?: false
    }

    /** 归还许可；取消路径也要归还，固定走 NonCancellable。 */
    suspend fun release() {
        withContext(NonCancellable) { releaseLocked() }
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
                .minByOrNull { position -> waiters[position].priority.rank } ?: return
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

    private suspend fun releaseLocked() {
        val next = lock.withLock {
            available++
            val index = waiters.indices
                .filter { position -> withinQuotaLocked(waiters[position]) }
                .minByOrNull { position -> waiters[position].priority.rank }
            if (index == null) {
                null
            } else {
                waiters.removeAt(index).also { available-- }
            }
        }
        next?.granted?.complete(Unit)
    }

    /** 排队者带优先级；派发顺序取 [PreviewPriority.rank]，同级保持等待顺序。 */
    private class Waiter(
        val priority: PreviewPriority,
        val granted: CompletableDeferred<Unit>,
    )
}

/** 取消原因的通用消息；这些不是调度器写入的具体原因，继续沿原因链找。 */
private val GENERIC_CANCEL_MESSAGES = setOf(
    "Job was cancelled",
    "Job was cancelled.",
    "Parent job is Cancelling",
    "Parent job is Cancelling.",
)

/** 调度器写入的取消原因；优先于异常链上的通用消息。 */
private val PREVIEW_CANCEL_REASONS = setOf("离屏", "列表重建", "换源", "订阅取消", "可见请求优先")

/** 取消原因：沿异常链优先找调度器写入的原因，再取第一条具体消息；都没有时按「取消」记录。 */
internal fun Throwable.cancelReason(): String {
    var fallback: String? = null
    var current: Throwable? = this
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        val message = current.message
        if (message != null && message in PREVIEW_CANCEL_REASONS) return message
        if (fallback == null && !message.isNullOrBlank() && message !in GENERIC_CANCEL_MESSAGES) {
            fallback = message
        }
        current = current.cause
    }
    return fallback ?: "取消"
}

/** 追异常原因的最大层数。 */
private const val MAX_CAUSE_DEPTH = 8

/**
 * 异常链最内层的原因类型；只取类名，原因消息可能含路径与服务器信息，不进日志。
 * 没有原因时返回 null；原因成环或超过 [maxDepth] 层时停在上限处，不追下去。
 */
internal fun Throwable.causeClassNameOrNull(maxDepth: Int = MAX_CAUSE_DEPTH): String? {
    var root: Throwable = this
    val seen = HashSet<Throwable>()
    seen += root
    var descended = false
    repeat(maxDepth) {
        val next = root.cause ?: return if (descended) root.javaClass.simpleName else null
        if (!seen.add(next)) return if (descended) root.javaClass.simpleName else null
        root = next
        descended = true
    }
    return root.javaClass.simpleName
}
