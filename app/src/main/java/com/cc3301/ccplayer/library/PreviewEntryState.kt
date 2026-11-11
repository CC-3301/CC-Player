package com.cc3301.ccplayer.library

/** 条目上次抽帧的结果。 */
internal sealed interface PreviewEntryResult {
    /** 出了图。 */
    data object Image : PreviewEntryResult

    /** 解码成功但没有可用图。 */
    data object NoImage : PreviewEntryResult

    /** 抽取失败；[stage] 是失败落在的那一环。 */
    data class Failed(val stage: PreviewFailureStage) : PreviewEntryResult
}

/**
 * 抽帧状态：一个条目在本次运行内的抽帧状况——上次结果、已试次数、最早可再试时刻、是否对预取抑制。
 * 由抽帧源持有，抽帧源与可见请求调度器读同一份；只描述结果，不含进行中的任务（ADR-0043）。
 */
class PreviewEntryState internal constructor() {

    /** 已试次数：连续失败累计，出图或确认无图后清零；网络条目从新一轮可见订阅起计。 */
    @Volatile
    var attempts: Int = 0
        private set

    /** 上次结果；还没有结果时为 null。 */
    @Volatile
    internal var result: PreviewEntryResult? = null
        private set

    /** 最早可再试时刻（单调毫秒）；0 表示没有退避。 */
    @Volatile
    internal var retryAtMillis: Long = 0
        private set

    /** 预取抑制：预取失败或无图后不再重复预取，变为可见时清除。 */
    @Volatile
    internal var prefetchSuppressed: Boolean = false
        private set

    /** 无图记忆的过期时刻；[result] 不是无图时为 null。 */
    @Volatile
    internal var noImageExpiresAtMillis: Long? = null
        private set

    /** 时长补读连续失败次数；退避按它成倍增长。 */
    @Volatile
    internal var durationAttempts: Int = 0
        private set

    /** 开始新一轮可见订阅：已试次数从零计，上次结果保留。 */
    @Synchronized
    internal fun resetAttempts() {
        attempts = 0
    }

    /** 记一次抽取失败；返回累计失败次数。 */
    @Synchronized
    internal fun fail(stage: PreviewFailureStage): Int {
        attempts++
        result = PreviewEntryResult.Failed(stage)
        noImageExpiresAtMillis = null
        return attempts
    }

    /** 给出图结果：失败计数、上次结果与预取抑制一起清掉。 */
    @Synchronized
    internal fun succeed() {
        attempts = 0
        result = PreviewEntryResult.Image
        noImageExpiresAtMillis = null
        prefetchSuppressed = false
    }

    /** 记「确认无图」：失败计数清零，到期后重新按可能有图处理。 */
    @Synchronized
    internal fun recordNoImage(expiresAtMillis: Long) {
        attempts = 0
        result = PreviewEntryResult.NoImage
        noImageExpiresAtMillis = expiresAtMillis
    }

    /** 只清「确认无图」，其余字段不动。 */
    @Synchronized
    internal fun forgetNoImage() {
        if (result != PreviewEntryResult.NoImage) return
        result = null
        noImageExpiresAtMillis = null
    }

    @Synchronized
    internal fun suppressPrefetch() {
        prefetchSuppressed = true
    }

    @Synchronized
    internal fun clearPrefetchSuppression() {
        prefetchSuppressed = false
    }

    /** 记一次时长补读失败；返回连续失败次数。 */
    @Synchronized
    internal fun failDuration(): Int {
        durationAttempts++
        return durationAttempts
    }

    /** 推迟到 [millis] 才允许重试。 */
    @Synchronized
    internal fun deferUntil(millis: Long) {
        retryAtMillis = millis
    }

    /** 时长补读恢复正常：失败计数与退避一起清掉。 */
    @Synchronized
    internal fun clearDurationFailure() {
        durationAttempts = 0
        retryAtMillis = 0
    }

    companion object {
        /** 「确认无图」的进程内记忆有效期：10 分钟。 */
        const val NO_IMAGE_TTL_MILLIS = 10 * 60 * 1000L
    }
}

/**
 * 抽帧状态表：一个条目一份状态，按最近使用排序保留有限条数。
 *
 * 容量满时淘汰最久未用的一条，其余记录的状态保持不动；记录满不再整体清空（ADR-0043）。
 * 无图记忆另带有效期，过期后按没有记录处理。
 */
internal class PreviewEntryStates(
    private val limit: Int = DEFAULT_LIMIT,
    private val clock: () -> Long = { systemMonotonicMillis() },
) {

    private val lock = Any()

    /** 键到状态；按最近使用排序，队首最久未用。 */
    private val states = LinkedHashMap<String, PreviewEntryState>()

    /** 取条目状态；没有记录或记录已过期时返回 null。取用刷新最近使用顺序。 */
    fun state(key: String): PreviewEntryState? = synchronized(lock) { stateLocked(key) }

    /** 取条目状态，没有记录时新建；记录满时先淘汰最久未用的一条。 */
    fun stateFor(key: String): PreviewEntryState = synchronized(lock) {
        stateLocked(key) ?: PreviewEntryState().also { state ->
            states[key] = state
            while (states.size > limit) states.remove(states.keys.first())
        }
    }

    /** 是否记着「确认无图」。 */
    fun isNoImageRemembered(key: String): Boolean = state(key)?.result == PreviewEntryResult.NoImage

    /** 记下「确认无图」；到期后重新按可能有图处理。 */
    fun rememberNoImage(key: String) {
        stateFor(key).recordNoImage(clock() + PreviewEntryState.NO_IMAGE_TTL_MILLIS)
    }

    private fun stateLocked(key: String): PreviewEntryState? {
        val state = states.remove(key) ?: return null
        if (state.noImageExpiresAtMillis?.let { expiresAt -> clock() >= expiresAt } == true) return null
        states[key] = state
        return state
    }

    companion object {
        /** 状态记录上限；超出时逐条淘汰最久未用的一条。 */
        const val DEFAULT_LIMIT = 512
    }
}
