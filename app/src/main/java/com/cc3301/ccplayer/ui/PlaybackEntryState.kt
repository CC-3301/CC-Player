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

    /** 已接手：播放控制层已接受这次打开，入口只等首个就绪或切集上报。 */
    Accepted,

    /** 失败：准备失败、目标不在列表或等待接手超时。 */
    Failed,

    /** 已取消：用户离开播放页或开始另一次打开。 */
    Cancelled,
}

/** 准备期：界面只留返回、底栏隐藏，返回按撤回或退出处理。 */
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
    private var awaitingHandoverMillis = 0L

    /** 当前这次打开的代际；没有进行中的打开时为 0。 */
    val generation: Int get() = currentGeneration ?: 0

    /** 准备期：界面只留返回、底栏隐藏。 */
    val isPreparing: Boolean get() = phase.isPreparingPhase

    /**
     * 开始一次新的打开并分配代际。
     *
     * [preparing] 为真表示准备在播放页内继续（视频），进入准备中；
     * 为假表示准备已经在浏览页完成（音频），进入时不显示准备期界面。
     */
    fun begin(preparing: Boolean): Int {
        generationSerial++
        currentGeneration = generationSerial
        awaitingHandoverMillis = 0L
        phase = if (preparing) EntryPhase.Preparing else null
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

    /** 准备失败或打开被中止；不是当前打开、已经结束时返回 false，调用方据此丢弃。 */
    fun failed(generation: Int): Boolean {
        if (!isCurrent(generation)) return false
        if (phase == EntryPhase.Failed || phase == EntryPhase.Cancelled) return false
        phase = EntryPhase.Failed
        return true
    }

    /** 等待接手阶段的前台计时；累计到时限返回 true。其余阶段不计时。 */
    fun onHandoverTick(deltaMillis: Long, foreground: Boolean): Boolean {
        if (phase != EntryPhase.AwaitingHandover || !foreground) return false
        awaitingHandoverMillis += deltaMillis.coerceAtLeast(0L)
        return awaitingHandoverMillis >= HANDOVER_TIMEOUT_MILLIS
    }

    /** 兜底到点：中止仍在等待接手的打开；已经接手或已结束时返回 false。 */
    fun abortAwaitingHandover(): Boolean {
        if (phase != EntryPhase.AwaitingHandover) return false
        phase = EntryPhase.Failed
        return true
    }

    /** 播放控制层上报状态：已接手后首次就绪或转入切集，入口阶段到此结束。 */
    fun onPlaybackUpdate(playbackState: PlaybackState, switching: Boolean) {
        if (phase != EntryPhase.Accepted) return
        if (switching || playbackState.isReady) {
            phase = null
            awaitingHandoverMillis = 0L
        }
    }

    /** 入口阶段未结束时立即显示缓冲指示；首个就绪后与控制层的缓冲状态交接。 */
    fun showsBuffering(controllerBuffering: Boolean): Boolean =
        controllerBuffering || phase != null && phase != EntryPhase.Failed && phase != EntryPhase.Cancelled

    /** 准备期与首次加载（非切集）只允许返回。 */
    fun initialLoading(playbackState: PlaybackState, switching: Boolean): Boolean =
        phase.isPreparingPhase || isInitialLoading(playbackState, switching)

    /** 首次播放加载失败：返回是否应退回列表，并清除入口阶段。 */
    fun consumeInitialFailure(): Boolean {
        if (phase != EntryPhase.Accepted) return false
        phase = EntryPhase.Failed
        return true
    }

    /** 离开播放页或开始另一次打开：作废当前代际，迟到的准备结果一律丢弃。 */
    fun cancelEntry() {
        phase = when (phase) {
            EntryPhase.Preparing, EntryPhase.AwaitingHandover -> EntryPhase.Cancelled
            else -> null
        }
        currentGeneration = null
        awaitingHandoverMillis = 0L
    }

    /** 清空入口阶段与代际。 */
    fun reset() {
        phase = null
        currentGeneration = null
        awaitingHandoverMillis = 0L
    }

    companion object {
        /** 准备完成到播放控制层接手的最长前台等待；到点中止本次打开并回滚。 */
        const val HANDOVER_TIMEOUT_MILLIS = 5_000L
    }
}

private val PlaybackState.isReady: Boolean
    get() = this == PlaybackState.Playing || this == PlaybackState.Paused
