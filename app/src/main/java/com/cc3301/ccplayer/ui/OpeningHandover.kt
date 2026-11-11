package com.cc3301.ccplayer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cc3301.ccplayer.library.MediaFile
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
}

/** 准备期：返回按撤回或退出处理，界面只保留返回与更多。 */
val EntryPhase?.isPreparingPhase: Boolean
    get() = this == EntryPhase.Preparing || this == EntryPhase.AwaitingHandover

/** 一次接手尝试的结论。 */
enum class HandoverAttempt {
    /** 还不能下发：没有待起播请求、已接手或已结束。 */
    NotYet,

    /** 播放控制层接受了这次打开。 */
    Accepted,

    /** 播放控制层不接受这次打开，本次打开已作废。 */
    Rejected,
}

/**
 * 打开交接：入口阶段、代际、兜底计时、待起播请求与起播握手。
 *
 * 一次打开的顺序：开始打开分配代际，准备完成留下待起播请求，界面在播放服务与画面就绪时尝试接手。
 * 接手标记与起播在同一个调用里完成，控制层不接受即作废这次打开。
 * 时间由外部推进，本类不自带协程；收尾动作由界面按返回值决定。
 *
 * 兜底只在等待接手阶段计时，只在前台累计；失败与取消直接清空阶段与代际。
 */
class OpeningHandover(
    private val startPlayback: (
        playlist: List<MediaFile>,
        current: MediaFile,
        generation: Int,
        directoryEntries: List<MediaFile>,
    ) -> Boolean,
    private val nowMillis: () -> Long,
) {

    /** 当前阶段；没有进行中的打开时为 null。 */
    var phase: EntryPhase? by mutableStateOf(null)
        private set

    private var generationSerial = 0
    private var currentGeneration: Int? = null

    /** 这次打开是在播放页里继续准备（视频）；音频的准备在浏览页完成。 */
    var preparesInPage = false
        private set

    /** 已接手但首次加载还没有结论：只有这个窗口内的首次加载失败才退回列表。 */
    private var initialLoadPending = false

    /** 准备完成后留下的待起播请求；接手时取走，只起播一次。 */
    private var pending: PendingRequest? = null

    private var elapsedMillis = 0L
    private var timingSince: Long? = null
    private var foreground = true

    /** 准备期界面：只给在播放页里等待准备的打开，音频进入时不显示准备期。 */
    val preparingUi: Boolean get() = preparesInPage && phase.isPreparingPhase

    /** 是否停在等待接手：界面据此决定要不要推进时间。 */
    val isAwaitingHandover: Boolean get() = phase == EntryPhase.AwaitingHandover

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
        pending = null
        resetTiming()
        phase = EntryPhase.Preparing
        return generationSerial
    }

    /** 这次上报是否仍属于当前打开。 */
    fun isCurrent(generation: Int): Boolean = generation != 0 && generation == currentGeneration

    /** 准备完成：准备中 -> 等待接手，并留下这次打开的待起播请求。返回结果是否仍属于当前打开。 */
    fun prepared(
        generation: Int,
        playlist: List<MediaFile>,
        current: MediaFile,
        directoryEntries: List<MediaFile>,
    ): Boolean {
        if (!isCurrent(generation)) return false
        pending = PendingRequest(playlist, current, generation, directoryEntries)
        if (phase == EntryPhase.Preparing) {
            phase = EntryPhase.AwaitingHandover
            if (foreground) resumeTiming()
        }
        return true
    }

    /**
     * 尝试接手：播放服务与画面就绪时调用。
     *
     * 接手标记与起播在同一个调用里完成：控制层接受才算接手，不接受即作废这次打开。
     */
    fun attemptHandover(canStart: Boolean): HandoverAttempt {
        if (!canStart) return HandoverAttempt.NotYet
        if (phase != EntryPhase.AwaitingHandover) return HandoverAttempt.NotYet
        val request = pending ?: return HandoverAttempt.NotYet
        pending = null
        if (!startPlayback(request.playlist, request.current, request.generation, request.directoryEntries)) {
            abandon()
            return HandoverAttempt.Rejected
        }
        phase = EntryPhase.Accepted
        initialLoadPending = true
        pauseTiming()
        return HandoverAttempt.Accepted
    }

    /** 时间推进一次：只在等待接手且在前台时累计；到点作废这次打开并返回 true。 */
    fun advance(): Boolean {
        if (phase != EntryPhase.AwaitingHandover) return false
        val since = timingSince ?: return false
        val now = nowMillis()
        timingSince = now
        elapsedMillis += (now - since).coerceAtLeast(0L)
        if (elapsedMillis < DEFAULT_TIMEOUT_MILLIS) return false
        abandon()
        return true
    }

    /** 前后台变化：退后台暂停计时，回前台在等待接手时接着累计。 */
    fun onForegroundChanged(foreground: Boolean) {
        this.foreground = foreground
        if (!foreground) {
            pauseTiming()
        } else if (phase == EntryPhase.AwaitingHandover) {
            resumeTiming()
        }
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
        abandon()
        return true
    }

    /** 取消指定的这次打开：清阶段、代际、待起播与计时；不是当前代际时返回 false。 */
    fun cancel(generation: Int): Boolean {
        if (!isCurrent(generation)) return false
        abandon()
        return true
    }

    /** 离开播放页：无条件作废当前这次打开。 */
    fun onLeftPlayerPage() {
        abandon()
        preparesInPage = false
    }

    /** 作废这次打开：阶段、代际、待起播与计时一起清空，迟到的结果一律丢弃。 */
    private fun abandon() {
        phase = null
        currentGeneration = null
        pending = null
        initialLoadPending = false
        resetTiming()
    }

    private fun resumeTiming() {
        timingSince = nowMillis()
    }

    private fun pauseTiming() {
        timingSince = null
    }

    private fun resetTiming() {
        elapsedMillis = 0L
        timingSince = null
    }

    private data class PendingRequest(
        val playlist: List<MediaFile>,
        val current: MediaFile,
        val generation: Int,
        val directoryEntries: List<MediaFile>,
    )

    private companion object {
        /** 准备完成到播放控制层接手的最长前台等待。 */
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}

private val PlaybackState.isReady: Boolean
    get() = this == PlaybackState.Playing || this == PlaybackState.Paused
