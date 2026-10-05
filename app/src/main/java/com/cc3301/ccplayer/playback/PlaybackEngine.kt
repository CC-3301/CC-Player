package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * 播放引擎接缝：控制层只依赖本接口，mpv 调用由 [MpvPlaybackEngine] 实现，测试用假实现驱动。
 */
interface PlaybackEngine {

    fun setListener(listener: Listener)

    /** 打开媒体并起播。 */
    fun start(url: String)

    fun pause()

    fun resume()

    fun stop()

    /** 跳到指定播放位置，单位毫秒。 */
    fun seekTo(positionMillis: Long)

    /** 设置本次会话的解码模式。 */
    fun setDecodeMode(mode: DecodeMode)

    /** 把画面接到视图的 Surface 上。 */
    fun attachSurface(surface: Surface)

    fun detachSurface()

    fun setSurfaceSize(width: Int, height: Int)

    /** 释放引擎持有的全部原生资源。 */
    fun release()

    /** 引擎向控制层上报的播放事件。 */
    interface Listener {
        /** 媒体已打开，可以出画出声。 */
        fun onMediaLoaded()

        /** 暂停状态变化。 */
        fun onPauseChanged(paused: Boolean)

        /** 当前媒体播放结束。 */
        fun onPlaybackEnded()

        /** 播放位置或时长更新，单位毫秒。 */
        fun onPositionChanged(positionMillis: Long, durationMillis: Long)

        /** 画面尺寸变化：宽高比按这对像素值推算；宽或高非正表示当前没有画面。 */
        fun onVideoSizeChanged(width: Int, height: Int)
    }
}
