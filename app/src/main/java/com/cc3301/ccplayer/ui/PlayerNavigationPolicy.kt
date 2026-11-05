package com.cc3301.ccplayer.ui

import com.cc3301.ccplayer.playback.OPEN_PLAYER_ACTION
import com.cc3301.ccplayer.playback.PlaybackState

/** 播放页系统返回的处理结果。 */
enum class PlayerBackAction {
    /** 取消进行中的切集，留在播放页。 */
    CancelSwitch,

    /** 结束播放并离开播放页。 */
    StopAndLeave,

    /** 离开播放页但保持播放，控制交给通知栏媒体卡片。 */
    LeaveAndContinue,
}

/**
 * 播放页系统返回的判定。
 *
 * 切集只取消这一跳；首次加载、恢复原片与错误态退出播放页；其余播放状态
 * 按「APP 内后台播放」决定离开后是否继续播放。
 */
fun playerBackAction(
    state: PlaybackState,
    switching: Boolean,
    restoring: Boolean,
    initialLoading: Boolean,
    failed: Boolean,
    inAppBackgroundPlayback: Boolean,
): PlayerBackAction = when {
    // 恢复原片期间 switching 仍为真，必须先按恢复态退出播放页。
    restoring -> PlayerBackAction.StopAndLeave
    initialLoading || failed -> PlayerBackAction.StopAndLeave
    switching -> PlayerBackAction.CancelSwitch
    state == PlaybackState.Idle || state == PlaybackState.Loading -> PlayerBackAction.StopAndLeave
    inAppBackgroundPlayback -> PlayerBackAction.LeaveAndContinue
    else -> PlayerBackAction.StopAndLeave
}

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
 * 通知卡片入口判定：只在首次创建且存在非空闲播放会话时回到播放页。
 * 界面重建重放旧 Intent 不得进入空白播放页。
 */
fun notificationOpenAction(
    action: String?,
    restored: Boolean,
    playbackState: PlaybackState?,
    playerVisible: Boolean,
): NotificationOpenAction = when {
    action != OPEN_PLAYER_ACTION -> NotificationOpenAction.Ignore
    restored -> NotificationOpenAction.Ignore
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
