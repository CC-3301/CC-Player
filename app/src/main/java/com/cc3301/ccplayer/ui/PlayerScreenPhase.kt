package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.PlaybackState

/**
 * 播放页加载状态的唯一事实：界面只判这一个值，不用多个布尔自行组合。
 */
enum class PlayerScreenPhase {
    /** 准备中：入口还在解析目录、外挂字幕或网络连接。 */
    Preparing,

    /** 等待接手：入口已经准备好，还没交给播放控制层。 */
    AwaitingHandover,

    /** 加载中：接手后的首次加载，首个就绪或切集之前。 */
    Loading,

    /** 切集中：正在加载切入的目标。 */
    Switching,

    /** 恢复中：切集失败或取消后重新打开原片。 */
    Restoring,

    /** 就绪：不在任何加载阶段。 */
    Ready,
}

/** 派生播放页阶段的事实。 */
data class PlayerScreenLoadFacts(
    /** 当前打开入口的阶段；没有进行中的打开时为 null。 */
    val entryPhase: EntryPhase?,
    /** 这次打开在播放页里继续准备（视频）；音频的准备在浏览页完成。 */
    val preparesInPage: Boolean,
    val playbackState: PlaybackState,
    val switching: Boolean,
    val restoring: Boolean,
)

/**
 * 由事实派生阶段：准备期优先，其后恢复原片、切集、首次加载，其余为就绪。
 *
 * 恢复原片期间切集标志仍为真，因此恢复先于切集判定。
 */
fun playerScreenPhase(facts: PlayerScreenLoadFacts): PlayerScreenPhase = when {
    facts.preparesInPage && facts.entryPhase == EntryPhase.Preparing -> PlayerScreenPhase.Preparing
    facts.preparesInPage && facts.entryPhase == EntryPhase.AwaitingHandover -> PlayerScreenPhase.AwaitingHandover
    facts.restoring -> PlayerScreenPhase.Restoring
    facts.switching -> PlayerScreenPhase.Switching
    // 「已接手」保留到这次打开被替换，不能当作首次加载的标志；加载中以控制层事实为准。
    facts.playbackState == PlaybackState.Loading -> PlayerScreenPhase.Loading
    else -> PlayerScreenPhase.Ready
}

/** 准备中与等待接手都属于准备期。 */
val PlayerScreenPhase.isPreparing: Boolean
    get() = this == PlayerScreenPhase.Preparing || this == PlayerScreenPhase.AwaitingHandover

/** 首次加载：准备期与接手后的加载中。 */
val PlayerScreenPhase.isInitialLoading: Boolean
    get() = isPreparing || this == PlayerScreenPhase.Loading

/** 是否正在切集。 */
val PlayerScreenPhase.isSwitching: Boolean
    get() = this == PlayerScreenPhase.Switching

/** 是否正在恢复原片。 */
val PlayerScreenPhase.isRestoring: Boolean
    get() = this == PlayerScreenPhase.Restoring
