package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.isInitialLoading

/** 一次打开的唯一步骤事实；同一时刻只有一个。 */
enum class EntryPhase {
    /** 准备中：入口还在解析目录、外挂字幕或网络连接。 */
    Preparing,

    /** 等待接手：已经准备好，但还没交给播放控制层。 */
    AwaitingHandover,

    /** 已接手：播放控制层已接受这次打开，入口只等首个就绪、切集或终态上报。 */
    Accepted,

    /** 失败：准备失败、目标不在列表或等待接手超时。 */
    Failed,
}

/** 准备期：返回按撤回或退出处理，界面只保留返回与更多。 */
val EntryPhase?.isPreparingPhase: Boolean
    get() = this == EntryPhase.Preparing || this == EntryPhase.AwaitingHandover

/**
 * 浏览页进入播放页的入口阶段。
 *
 * 只维护一个阶段值：界面显隐、兜底计时与收尾决策都从它推导。
 * 每次打开分配一个代际号，准备的成功与失败带同一代际才被接受，判定用严格相等。
 */
class PlaybackEntryState {

    /** 当前阶段；没有进行中的打开时为 null。 */
    var phase: EntryPhase? by mutableStateOf(null)
        private set

    private var generationSerial = 0
    private var currentGeneration: Int? = null

    /** 这次打开是在播放页里继续准备（视频）；音频的准备在浏览页完成。 */
    private var preparesInPage = false

    private var handoverElapsedMillis = 0L
    private var lastHandoverTickMillis: Long? = null

    /** 当前这次打开的代际；没有进行中的打开时为 0。 */
    val generation: Int get() = currentGeneration ?: 0

    /** 准备期界面：只给在播放页里等待准备的打开，音频进入时不显示准备期。 */
    val preparingUi: Boolean get() = preparesInPage && phase.isPreparingPhase

    /**
     * 开始一次新的打开并分配代际。
     *
     * [preparing] 只决定准备期界面是否显示；准备本身总是在当前页面进行。
     */
    fun begin(preparing: Boolean): Int {
        generationSerial++
        currentGeneration = generationSerial
        preparesInPage = preparing
        handoverElapsedMillis = 0L
        lastHandoverTickMillis = null
        phase = EntryPhase.Preparing
        return generationSerial
    }

    /** 这次上报是否仍属于当前打开。 */
    fun isCurrent(generation: Int): Boolean = generation != 0 && generation == currentGeneration

    /** 准备完成：准备中 -> 等待接手。返回这次结果是否仍属于当前打开。 */
    fun prepared(generation: Int): Boolean {
        if (!isCurrent(generation)) return false
        if (phase == EntryPhase.Preparing) phase = EntryPhase.AwaitingHandover
        return true
    }

    /** 播放控制层接受了这次打开。 */
    fun accepted(generation: Int): Boolean {
        if (!isCurrent(generation)) return false
        if (phase == EntryPhase.AwaitingHandover) phase = EntryPhase.Accepted
        return true
    }

    /** 准备失败或打开被中止；不是当前打开时返回 false，调用方据此丢弃。 */
    fun failed(generation: Int): Boolean {
        if (!isCurrent(generation)) return false
        phase = EntryPhase.Failed
        currentGeneration = null
        lastHandoverTickMillis = null
        return true
    }

    /**
     * 等待接手阶段的计时：只累计两次 tick 之间的实际前台时长。
     * 不在该阶段或已退后台时只清基线，不累计。
     */
    fun onHandoverTick(nowMillis: Long, foreground: Boolean): Boolean {
        if (phase != EntryPhase.AwaitingHandover || !foreground) {
            lastHandoverTickMillis = null
            return false
        }
        val last = lastHandoverTickMillis
        lastHandoverTickMillis = nowMillis
        if (last == null) return false
        handoverElapsedMillis += (nowMillis - last).coerceAtLeast(0L)
        return handoverElapsedMillis >= HANDOVER_TIMEOUT_MILLIS
    }

    /** 退后台或停止 ticker 时丢弃基线，避免把暂停期间算进前台时长。 */
    fun pauseHandoverTimer() {
        lastHandoverTickMillis = null
    }

    /** 兜底到点：中止仍在等待接手的打开；已经接手或已结束时返回 false。 */
    fun failAwaitingHandover(): Boolean {
        if (phase != EntryPhase.AwaitingHandover) return false
        phase = EntryPhase.Failed
        currentGeneration = null
        lastHandoverTickMillis = null
        return true
    }

    /** 播放控制层上报状态：已接手后首个就绪、切集或终态上报即结束入口阶段。 */
    fun onPlaybackUpdate(playbackState: PlaybackState, switching: Boolean) {
        if (phase != EntryPhase.Accepted) return
        if (
            switching ||
            playbackState.isReady ||
            playbackState == PlaybackState.Ended ||
            playbackState == PlaybackState.Idle
        ) {
            phase = null
            lastHandoverTickMillis = null
        }
    }

    /** 入口阶段未结束时立即显示缓冲指示；首个就绪后与控制层的缓冲状态交接。 */
    fun showsBuffering(controllerBuffering: Boolean): Boolean =
        controllerBuffering || (
            preparesInPage &&
                (phase == EntryPhase.Preparing || phase == EntryPhase.AwaitingHandover || phase == EntryPhase.Accepted)
            )

    /** 准备期与首次加载（非切集）只允许返回。 */
    fun initialLoading(playbackState: PlaybackState, switching: Boolean): Boolean =
        phase.isPreparingPhase || isInitialLoading(playbackState, switching)

    /**
     * 首次加载失败：只接受带当前代际的首次加载失败，并且只对在播放页里准备的打开生效。
     * 音频首播失败留在播放页。
     */
    fun consumeInitialFailure(generation: Int, firstLoad: Boolean): Boolean {
        if (!firstLoad || !preparesInPage || !isCurrent(generation)) return false
        // 准备中还没有交给控制层，不可能有本次加载的失败；此时只可能是上一会话的迟到上报。
        if (phase == EntryPhase.Preparing) return false
        phase = EntryPhase.Failed
        currentGeneration = null
        lastHandoverTickMillis = null
        return true
    }

    /** 离开播放页或开始另一次打开：作废当前代际与阶段，迟到的准备结果一律丢弃。 */
    fun cancelEntry() {
        phase = null
        currentGeneration = null
        preparesInPage = false
        handoverElapsedMillis = 0L
        lastHandoverTickMillis = null
    }

    companion object {
        /** 准备完成到播放控制层接手的最长前台等待；到点中止本次打开并回滚。 */
        const val HANDOVER_TIMEOUT_MILLIS = 5_000L
    }
}

private val PlaybackState.isReady: Boolean
    get() = this == PlaybackState.Playing || this == PlaybackState.Paused
