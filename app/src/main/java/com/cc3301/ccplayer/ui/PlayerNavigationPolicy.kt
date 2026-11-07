package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.playback.OPEN_PLAYER_ACTION
import com.cc3301.ccplayer.playback.PlaybackState

/** 播放入口准备失败后的收尾结果。 */
enum class FailedEntryExit {
    /** 已有播放会话且不在片尾：只离开播放页，保留原会话继续播放。 */
    LeavePlayerPage,

    /** 没有可用会话或旧会话停在片尾：结束播放并离开播放页。 */
    StopPlayback,
}

/** 入口准备失败时按现有播放会话决定是否结束播放；片尾停留按普通退出处理。 */
fun failedEntryExit(playbackState: PlaybackState?, atEpisodeEnd: Boolean): FailedEntryExit =
    if (playbackState != null && playbackState != PlaybackState.Idle && !atEpisodeEnd) {
        FailedEntryExit.LeavePlayerPage
    } else {
        FailedEntryExit.StopPlayback
    }

/** 接手兜底到点使用的既有失败提示。 */
val HANDOVER_TIMEOUT_FAILURE: MediaFailure = MediaFailure.Unknown

/** 接手兜底到点的收尾：先给既有失败提示，再按现有会话撤回或退出。 */
data class EntryFailureOutcome(
    val failure: MediaFailure,
    val exit: FailedEntryExit,
)

fun handoverTimeoutOutcome(playbackState: PlaybackState?, atEpisodeEnd: Boolean): EntryFailureOutcome =
    EntryFailureOutcome(
        failure = HANDOVER_TIMEOUT_FAILURE,
        exit = failedEntryExit(playbackState, atEpisodeEnd),
    )

/** 播放页系统返回的处理结果。 */
enum class PlayerBackAction {
    /** 取消进行中的切集，留在播放页。 */
    CancelSwitch,

    /** 打开尚未真正开始：撤回打开交接，旧会话恢复出声与画面后离开播放页。 */
    CancelOpening,

    /** 结束播放并离开播放页。 */
    StopAndLeave,

    /** 离开播放页但保持播放，控制交给通知栏媒体卡片。 */
    LeaveAndContinue,
}

/**
 * 播放页返回的判定。
 *
 * 准备期（准备中与等待接手）按撤回或退出处理：有旧会话且不在片尾就撤回打开交接，
 * 没有旧会话或旧会话停在片尾就退出播放页。其余状态：切集只取消这一跳；
 * 首次加载、恢复原片与错误态退出播放页；其余按「APP 内后台播放」决定离开后是否继续播放。
 */
fun playerBackAction(
    state: PlaybackState,
    switching: Boolean,
    restoring: Boolean,
    initialLoading: Boolean,
    failed: Boolean,
    inAppBackgroundPlayback: Boolean,
    entryPhase: EntryPhase? = null,
    atEpisodeEnd: Boolean = false,
): PlayerBackAction = when {
    // 恢复原片期间 switching 仍为真，必须先按恢复态退出播放页。
    restoring -> PlayerBackAction.StopAndLeave
    // 失败优先于准备期：已经失败时返回一律结束播放，不再撤回交接。
    failed -> PlayerBackAction.StopAndLeave
    // 准备期：有旧会话就撤回交接，否则按普通退出处理；片尾停留不撤回。
    entryPhase.isPreparingPhase ->
        if (state != PlaybackState.Idle && !atEpisodeEnd) {
            PlayerBackAction.CancelOpening
        } else {
            PlayerBackAction.StopAndLeave
        }
    initialLoading -> PlayerBackAction.StopAndLeave
    switching -> PlayerBackAction.CancelSwitch
    state == PlaybackState.Idle || state == PlaybackState.Loading -> PlayerBackAction.StopAndLeave
    inAppBackgroundPlayback -> PlayerBackAction.LeaveAndContinue
    else -> PlayerBackAction.StopAndLeave
}

/** 顶栏返回是否按「总是结束播放」处理；准备期它与系统返回等价，交给同一套判定。 */
fun playerTopBarBackEndsPlayback(entryPhase: EntryPhase?): Boolean = !entryPhase.isPreparingPhase

/** 面板或抽屉打开时，系统返回只消费为关闭面板，不离开播放页。 */
fun playerBackClosesPanel(anyPanelOpen: Boolean): Boolean = anyPanelOpen

/** 库页面点开一条媒体时的处理结果。 */
enum class LibraryEntryAction {
    /** 点的是当前正在播放的条目：回到播放页接着播，不重新加载。 */
    ReopenPlayerPage,

    /** 按普通入口打开或替换当前播放。 */
    OpenEntry,
}

/** 通知卡片点击的处理结果。 */
enum class NotificationOpenAction {
    /** 不处理：动作不匹配、界面重建、没有播放会话或播放页已在前台。 */
    Ignore,

    /** 回到已有播放会话的播放页。 */
    ShowPlayerPage,
}

/**
 * 通知卡片入口判定：只在 Intent 尚未消费且存在非空闲播放会话时回到播放页。
 * 已消费的 Intent 动作已清空；进程已死亡时 playback 为空，同样不会进入空白播放页。
 */
fun notificationOpenAction(
    action: String?,
    playbackState: PlaybackState?,
    playerVisible: Boolean,
): NotificationOpenAction = when {
    action != OPEN_PLAYER_ACTION -> NotificationOpenAction.Ignore
    playbackState == null || playbackState == PlaybackState.Idle -> NotificationOpenAction.Ignore
    playerVisible -> NotificationOpenAction.Ignore
    else -> NotificationOpenAction.ShowPlayerPage
}

/** 库页面点开条目的判定：正在播放的条目只回到播放页，其余条目直接打开。 */
fun libraryEntryAction(
    entryLocation: String,
    currentLocation: String?,
    playbackActive: Boolean,
): LibraryEntryAction =
    if (playbackActive && currentLocation == entryLocation) {
        LibraryEntryAction.ReopenPlayerPage
    } else {
        LibraryEntryAction.OpenEntry
    }
