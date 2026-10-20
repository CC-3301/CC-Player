package com.cc3301.ccplayer.playback

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
