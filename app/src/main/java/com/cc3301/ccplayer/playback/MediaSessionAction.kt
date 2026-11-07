package com.cc3301.ccplayer.playback

import android.support.v4.media.session.PlaybackStateCompat

/**
 * 通知栏媒体动作：通知栏按钮与系统媒体控制都下发这四种。
 */
enum class MediaSessionAction(
    /** 通知栏按钮 Intent 的 action，服务据此取回动作。 */
    val intentAction: String,
) {
    Play("com.cc3301.ccplayer.action.PLAY"),
    Pause("com.cc3301.ccplayer.action.PAUSE"),
    Previous("com.cc3301.ccplayer.action.PREVIOUS"),
    Next("com.cc3301.ccplayer.action.NEXT");

    /** 把动作交给播放控制层执行。 */
    fun applyTo(controller: PlaybackController) {
        when (this) {
            Play -> controller.resume()
            Pause -> controller.pause()
            Previous -> controller.previous()
            Next -> controller.next()
        }
    }

    companion object {
        /** 由通知栏按钮 Intent 的 action 取回动作；不是通知栏动作时为 null。 */
        fun fromIntentAction(action: String?): MediaSessionAction? =
            entries.firstOrNull { it.intentAction == action }
    }
}

/** 通知栏播放/暂停按钮的动作：播放中显示暂停，其余状态显示播放。 */
fun mediaSessionActionFor(state: PlaybackState): MediaSessionAction =
    if (state == PlaybackState.Playing) MediaSessionAction.Pause else MediaSessionAction.Play

/**
 * 媒体会话动作的可用性：后台关闭时只放行暂停；首次加载全部禁用；
 * 目标加载与恢复原片与播放页同一套边界，四个动作全部放行
 * （播放/暂停按起播暂停意图排队，上一集/下一集每击前移目标），这两段加载态不再收窄动作集。
 */
fun mediaActionAllowed(
    action: MediaSessionAction,
    initialLoading: Boolean,
    blocksBackgroundPlayback: Boolean = false,
): Boolean = when {
    blocksBackgroundPlayback -> action == MediaSessionAction.Pause
    initialLoading -> false
    else -> true
}

/** 当前加载阶段下可用的媒体动作，通知栏与 MediaSession 的动作集合据此收窄。 */
fun availableMediaActions(
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
): List<MediaSessionAction> =
    MediaSessionAction.entries.filter { mediaActionAllowed(it, initialLoading) }

/**
 * 通知栏与系统媒体控制的统一下发入口：动作在当前加载阶段不可用时不下发。
 * [state] 来自播放控制层；[switching] 决定当前是否仍算首次加载。
 */
fun dispatchMediaAction(
    action: MediaSessionAction,
    state: PlaybackState,
    switching: Boolean,
    restoring: Boolean,
    blocksBackgroundPlayback: Boolean = false,
    apply: (MediaSessionAction) -> Unit,
) {
    val initialLoading = isInitialLoading(state, switching)
    if (mediaActionAllowed(action, initialLoading, blocksBackgroundPlayback)) {
        apply(action)
    }
}

/** 通知栏动作集合：后台关闭时只保留暂停，其余情况按加载阶段收窄。 */
fun mediaNotificationActions(
    state: PlaybackState,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
    blocksBackgroundPlayback: Boolean,
): List<MediaSessionAction> =
    if (blocksBackgroundPlayback) {
        listOf(MediaSessionAction.Pause)
    } else {
        listOf(MediaSessionAction.Previous, mediaSessionActionFor(state), MediaSessionAction.Next)
            .filter { mediaActionAllowed(it, initialLoading) }
    }

/** 拖动定位只在正常播放或暂停且未被加载阶段、后台策略限制时可用。 */
fun mediaSeekAllowed(
    state: PlaybackState,
    hasMedia: Boolean,
    switching: Boolean,
    initialLoading: Boolean,
    restoring: Boolean,
    blocksBackgroundPlayback: Boolean,
): Boolean = (state == PlaybackState.Playing || state == PlaybackState.Paused) &&
    hasMedia && !switching && !initialLoading && !restoring && !blocksBackgroundPlayback

/** 媒体动作集合对应的 [PlaybackStateCompat] 动作位。 */
fun playbackStateActions(actions: List<MediaSessionAction>, seekable: Boolean = false): Long {
    val flags = actions.fold(0L) { flags, action ->
        flags or when (action) {
            MediaSessionAction.Play -> PlaybackStateCompat.ACTION_PLAY
            MediaSessionAction.Pause -> PlaybackStateCompat.ACTION_PAUSE
            MediaSessionAction.Previous -> PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            MediaSessionAction.Next -> PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        }
    }
    return if (seekable) flags or PlaybackStateCompat.ACTION_SEEK_TO else flags
}
