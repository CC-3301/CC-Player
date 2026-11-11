package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.ccplayer.playback.PlaybackState
import com.cc3301.ccplayer.playback.isInitialLoading

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

    /** 已接手但首次加载还没有结论：只有这个窗口内的首次加载失败才退回列表。 */
    private var initialLoadPending = false

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
        initialLoadPending = false
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

    /** 播放控制层接受了这次打开：首次加载窗口从此开始。 */
    fun accepted(generation: Int): Boolean {
        if (!isCurrent(generation)) return false
        if (phase == EntryPhase.AwaitingHandover) phase = EntryPhase.Accepted
        initialLoadPending = true
        return true
    }

    /** 准备失败或打开被中止；不是当前打开时返回 false，调用方据此丢弃。 */
    fun failed(generation: Int): Boolean {
        if (!isCurrent(generation)) return false
        phase = null
        currentGeneration = null
        initialLoadPending = false
        return true
    }

    /** 兜底到点：中止仍在等待接手的打开；已经接手或已结束时返回 false。 */
    fun failAwaitingHandover(): Boolean {
        if (phase != EntryPhase.AwaitingHandover) return false
        phase = null
        currentGeneration = null
        initialLoadPending = false
        return true
    }

    /**
     * 播放控制层上报状态：已接手后首个就绪或切集即结束入口阶段，首次加载窗口同时关闭。
     * 已结束与空闲也结束入口阶段，但保留首次加载窗口：加载失败事件随后到达时仍要退回列表。
     */
    fun onPlaybackUpdate(playbackState: PlaybackState, switching: Boolean) {
        if (phase != EntryPhase.Accepted) return
        if (switching || playbackState.isReady) {
            phase = null
            initialLoadPending = false
            return
        }
        if (playbackState == PlaybackState.Ended || playbackState == PlaybackState.Idle) {
            phase = null
        }
    }

    /** 入口阶段未结束时立即显示缓冲指示；首个就绪后与控制层的缓冲状态交接。 */
    fun showsBuffering(controllerBuffering: Boolean): Boolean =
        controllerBuffering || (
            preparesInPage &&
                (phase == EntryPhase.Preparing || phase == EntryPhase.AwaitingHandover || phase == EntryPhase.Accepted)
            )

    /** 准备期与首次加载（非切集）只允许返回与不依赖加载的控件。 */
    fun initialLoading(playbackState: PlaybackState, switching: Boolean): Boolean =
        phase.isPreparingPhase || isInitialLoading(playbackState, switching)

    /**
     * 首次加载失败：只接受当前代际、首次加载窗口内、且是在播放页里准备的打开。
     * 窗口外的后续播放失败与音频首播失败都不关播放页。
     */
    fun consumeInitialFailure(generation: Int, firstLoad: Boolean): Boolean {
        if (!firstLoad || !preparesInPage || !initialLoadPending || !isCurrent(generation)) return false
        initialLoadPending = false
        phase = null
        currentGeneration = null
        return true
    }

    /** 离开播放页或开始另一次打开：作废当前代际、阶段与首次加载窗口，迟到的结果一律丢弃。 */
    fun cancelEntry() {
        phase = null
        currentGeneration = null
        preparesInPage = false
        initialLoadPending = false
    }
}

private val PlaybackState.isReady: Boolean
    get() = this == PlaybackState.Playing || this == PlaybackState.Paused
