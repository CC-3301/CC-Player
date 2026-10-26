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
     * 返回本次加载的代际序号；后续加载回调带上同一序号，控制层据此丢弃迟到的旧事件。
     */
    fun start(url: String, paused: Boolean = false, startPositionMillis: Long = 0): Int

    /**
     * 取当前画面的一帧。非 null 表示确实有帧可显示；位图本身可为 null 仅用于测试替身。
     */
    fun grabFrame(): Frame?

    /** 引擎抓到的一帧画面；返回 null 表示无帧，位图可为 null 仅供测试替身。 */
    class Frame(val bitmap: Bitmap?)

    fun pause()

    fun resume()

    fun stop()

    /**
     * 丢弃所有在途加载代际。加载看门狗判失败后，在途加载可能永远不会再产生事件，
     * 恢复原片前必须先丢掉它，否则恢复的 FILE_LOADED 会配到旧代际。
     */
    fun discardInFlightLoads()

    /**
     * 跳到指定播放位置，单位毫秒。
     * 返回本次 seek 的序号；之后的 ready 回调带上同一序号，控制层据此只认 seek 之后的信号。
     */
    fun seekTo(positionMillis: Long): Int

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

    /** 当前 Surface 代数；每次重新挂载后递增，用于丢弃迟到的画面事件。 */
    val surfaceGeneration: Int

    /** 把画面接到视图的 Surface 上。 */
    fun attachSurface(surface: Surface)

    fun detachSurface()

    fun setSurfaceSize(width: Int, height: Int)

    /** 释放引擎持有的全部原生资源。 */
    fun release()

    /** 引擎向控制层上报的播放事件。 */
    interface Listener {
        /**
         * 媒体文件已打开，轨道信息可用；首帧可能还没画出来。
         * [targetUrl] 与 [loadToken] 标识本次加载，[hasVideo] 表示是否有视频轨（纯音频没有新视频帧）。
         */
        fun onMediaLoaded(targetUrl: String?, loadToken: Int, hasVideo: Boolean)

        /**
         * 首帧已可显示。[needsFrameConfirmation] 为真表示信号本身不能证明画面已上屏
         * （视频链重配，或暂停态下的 PLAYBACK_RESTART），控制层要等到真正出帧后再算就绪。
         * [targetUrl] 是本次加载的目标，控制层据此丢弃取消/恢复之后迟到的 ready。
         * [seekSerial] 是本次信号对应的 seek 序号，控制层只接受续播 seek 之后的信号。
         * [loadToken] 是产生该信号的那次加载，控制层据此丢弃同 URL 重载后的迟到事件。
         * seek 后引擎会再次上报 PLAYBACK_RESTART。
         */
        fun onFirstFrameReady(
            needsFrameConfirmation: Boolean,
            targetUrl: String?,
            seekSerial: Int,
            loadToken: Int,
        )

        /**
         * 收到任一加载相关事件；用于重置“完全无事件”看门狗。
         * [activity] 是事件来源，控制层据此区分真实加载活动与自身 resume 的回声；
         * [loadToken] 是产生它的加载，未知时为 null。
         */
        fun onLoadActivity(activity: LoadActivity, loadToken: Int?)

        /**
         * 新画面已真正输出到 Surface：加载已就绪后播放位置实际前进，或重新挂载 Surface 后再次前进。
         * [surfaceGeneration] 标识本次挂载，[loadToken] 标识产生它的加载。
         * 暂停态没有位置前进，不能凭视频输出重配冒充这个信号。
         */
        fun onFramePresented(surfaceGeneration: Int, loadToken: Int)

        /** 暂停状态变化。 */
        fun onPauseChanged(paused: Boolean)

        /** 当前媒体自然播放结束；[loadToken] 标识产生它的加载。被替换或停止产生的结束事件不上报。 */
        fun onPlaybackEnded(loadToken: Int)

        /**
         * 媒体打开失败或播放中出错，按切集失败处理。
         * [loadToken] 标识产生失败的加载，控制层据此丢弃取消或重定向后迟到的旧目标失败；
         * [errorText] 是播放核心给出的错误文本。
         */
        fun onPlaybackFailed(loadToken: Int, errorText: String?)

        /** 播放位置或时长更新，单位毫秒。 */
        fun onPositionChanged(positionMillis: Long, durationMillis: Long)

        /** 画面尺寸变化：宽高比按这对像素值推算；宽或高非正表示当前没有画面。 */
        fun onVideoSizeChanged(width: Int, height: Int)
    }
}

/** 加载相关事件的来源；控制层只把它当作活动分类，不依赖 mpv 事件编号。 */
enum class LoadActivity {
    StartFile,
    FileLoaded,
    Seek,
    VideoReconfig,
    AudioReconfig,
    PlaybackRestart,
    EndFile,
}
