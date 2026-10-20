package com.cc3301.ccplayer.playback

import android.graphics.Bitmap
import android.view.Surface

/**
 * 播放引擎接缝：控制层只依赖本接口，mpv 调用由 [MpvPlaybackEngine] 实现，测试用假实现驱动。
 */
interface PlaybackEngine {

    fun setListener(listener: Listener)

    /**
     * 打开媒体；[paused] 为真时新文件加载后保持暂停，等控制层放行才出声。
     * [startPositionMillis] 为正时从该位置打开，首帧就是目标位置的画面。
     * 每次开始都显式设置暂停状态，避免上一次会话的暂停残留。
     */
    fun start(url: String, paused: Boolean = false, startPositionMillis: Long = 0)

    /** 取当前画面的一帧，用于切集时冻结在原画面上；没有可取的画面时为 null。 */
    fun grabFrame(): Bitmap?

    fun pause()

    fun resume()

    fun stop()

    /** 跳到指定播放位置，单位毫秒。 */
    fun seekTo(positionMillis: Long)

    /** 从当前位置起对音频输出做淡入，时长 [durationMillis]；画面不受影响。 */
    fun fadeInAudio(durationMillis: Long)

    /** 设置本次会话的解码模式。 */
    fun setDecodeMode(mode: DecodeMode)

    /** 当前媒体的内嵌字幕轨道；没有媒体或没有内嵌字幕时为空。 */
    fun embeddedSubtitleTracks(): List<SubtitleTrack>

    /** 把外挂字幕加载进轨道列表；不改变当前显示的哪一路。 */
    fun addExternalSubtitle(location: String)

    /** 显示内嵌字幕轨道 [trackId]；外挂字幕随之停显。 */
    fun selectEmbeddedSubtitle(trackId: Int)

    /** 显示已加载的外挂字幕 [location]；内嵌字幕随之停显。 */
    fun selectExternalSubtitle(location: String)

    /** 关闭字幕显示。 */
    fun disableSubtitles()

    /** 把画面接到视图的 Surface 上。 */
    fun attachSurface(surface: Surface)

    fun detachSurface()

    fun setSurfaceSize(width: Int, height: Int)

    /** 释放引擎持有的全部原生资源。 */
    fun release()

    /** 引擎向控制层上报的播放事件。 */
    interface Listener {
        /** 媒体文件已打开，轨道信息可用；首帧可能还没画出来。 */
        fun onMediaLoaded()

        /** 首帧已可显示，缓冲可以结束。 */
        fun onFirstFrameReady()

        /** 暂停状态变化。 */
        fun onPauseChanged(paused: Boolean)

        /** 当前媒体自然播放结束；被替换或停止产生的结束事件不上报。 */
        fun onPlaybackEnded()

        /** 媒体打开失败或播放中出错，按切集失败处理。 */
        fun onPlaybackFailed()

        /** 播放位置或时长更新，单位毫秒。 */
        fun onPositionChanged(positionMillis: Long, durationMillis: Long)

        /** 画面尺寸变化：宽高比按这对像素值推算；宽或高非正表示当前没有画面。 */
        fun onVideoSizeChanged(width: Int, height: Int)
    }
}
