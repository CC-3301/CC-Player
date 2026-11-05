package com.cc3301.ccplayer.ui

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
    switching -> PlayerBackAction.CancelSwitch
    initialLoading || restoring || failed -> PlayerBackAction.StopAndLeave
    state == PlaybackState.Idle || state == PlaybackState.Loading -> PlayerBackAction.StopAndLeave
    inAppBackgroundPlayback -> PlayerBackAction.LeaveAndContinue
    else -> PlayerBackAction.StopAndLeave
}

/** 库页面点开一条媒体时的处理结果。 */
enum class LibraryEntryAction {
    /** 点的是当前正在播放的条目：回到播放页接着播，不重新加载。 */
    ReopenPlayerPage,

    /** 按普通入口打开或替换当前播放。 */
    OpenEntry,
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
