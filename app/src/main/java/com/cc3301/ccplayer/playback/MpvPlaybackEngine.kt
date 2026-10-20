package com.cc3301.ccplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import java.util.Locale

/**
 * 用 mpv-android 官方 libmpv 构建产物实现的播放引擎。
 *
 * 渲染入口是 [MPVLib.attachSurface]：libmpv 的 render API 直接把画面画到视图的 Surface 上，
 * 相当于 mpv-android 示例工程里 MPVView 承担的渲染职责。
 */
class MpvPlaybackEngine(
    private val context: Context,
    private val cacheDir: String,
) : PlaybackEngine, MPVLib.EventObserver {

    private var listener: PlaybackEngine.Listener? = null

    /** detachSurface 会把视频输出关掉，重新接上 Surface 前要恢复它。 */
    private var videoOutputStopped = false

    /** 当前挂着的 Surface；重复挂载同一块时不重建渲染上下文。 */
    private var attachedSurface: Surface? = null

    /** 加载代际与结束事件判定；方法内部同步，控制层与事件线程都会调用。 */
    private val fileEvents = MpvFileEvents()

    /** seek 序号与 ready 上报判定；旧事件不会被当成 seek 之后的信号。 */
    private val readySignals = MpvReadySignals()

    /** 当前文件是否有视频轨道；纯音频没有首帧，文件打开即可视为就绪。 */
    @Volatile
    private var hasVideoTrack = false

    /** 本次 loadfile 的目标位置，用 mpv 的 path 属性识别迟到的旧 FILE_LOADED。 */
    @Volatile
    private var targetUrl: String? = null

    @Volatile
    private var positionSeconds = 0.0

    @Volatile
    private var durationSeconds = 0.0
    private var videoWidth = 0
    private var videoHeight = 0

    fun initialize() {
        MPVLib.create(context.applicationContext)
        MpvOptions.initial(cacheDir).forEach { (option, value) -> MPVLib.setOptionString(option, value) }
        MPVLib.init()
        MPVLib.addObserver(this)
        MPVLib.observeProperty(PAUSE_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty(TIME_POS_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty(DURATION_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty(WIDTH_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty(HEIGHT_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty(EOF_REACHED_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    override fun setListener(listener: PlaybackEngine.Listener) {
        this.listener = listener
    }

    override fun start(url: String, paused: Boolean, startPositionMillis: Long) {
        positionSeconds = 0.0
        durationSeconds = 0.0
        hasVideoTrack = false
        readySignals.onStart()
        targetUrl = url
        fileEvents.onStart()
        MPVLib.setPropertyBoolean(PAUSE_PROPERTY, paused)
        val command = mutableListOf("loadfile", url, "replace")
        if (startPositionMillis > 0) {
            command += "-1"
            command += "start=${formatSeconds(startPositionMillis)}"
        }
        MPVLib.command(command.toTypedArray())
    }

    /** mpv 的缩略图接口直接取当前视频帧；没有画面时返回 null。 */
    override fun grabFrame(): PlaybackEngine.Frame? =
        runCatching { MPVLib.grabThumbnail(GRAB_DIMENSION) }.getOrNull()?.let(PlaybackEngine::Frame)

    override fun pause() {
        MPVLib.setPropertyBoolean(PAUSE_PROPERTY, true)
    }

    override fun resume() {
        MPVLib.setPropertyBoolean(PAUSE_PROPERTY, false)
    }

    override fun stop() {
        // 停止产生的 END_FILE 由待吞状态消化，不能当成播放结束或失败。
        fileEvents.onStop()
        hasVideoTrack = false
        targetUrl = null
        MPVLib.command(arrayOf("stop"))
    }

    /**
     * seek 后 mpv 会再发 PLAYBACK_RESTART。序号在这里下发，
     * 但要等 MPV_EVENT_SEEK 到达后才用于标记 ready，迟到的旧事件仍带旧序号。
     */
    override fun seekTo(positionMillis: Long): Int {
        positionSeconds = positionMillis / 1000.0
        val serial = readySignals.onSeekIssued()
        MPVLib.command(arrayOf("seek", formatSeconds(positionMillis), "absolute"))
        return serial
    }

    /** 用 mpv 的 lavfi 桥接 FFmpeg 的 afade：淡入从当前播放位置起算，只作用于音频链。 */
    override fun fadeInAudio(durationMillis: Long) {
        val fade = "afade=t=in:st=${formatSeconds(toMillis(positionSeconds))}:d=${formatSeconds(durationMillis)}"
        MPVLib.command(arrayOf("af", "set", "lavfi=[$fade]"))
    }

    override fun setDecodeMode(mode: DecodeMode) {
        val hwdec = if (mode == DecodeMode.Hardware) MpvOptions.HWDEC else MpvOptions.HWDEC_SOFTWARE
        MPVLib.setPropertyString(HWDEC_PROPERTY, hwdec)
    }

    /** 遍历 track-list，只取非外挂的字幕轨道。 */
    override fun embeddedSubtitleTracks(): List<SubtitleTrack> = trackIndexes().mapNotNull { index ->
        if (trackPropertyString(index, TYPE_ENTRY) != SUBTITLE_TRACK_TYPE) return@mapNotNull null
        if (trackPropertyBoolean(index, EXTERNAL_ENTRY) == true) return@mapNotNull null
        val id = trackPropertyInt(index, ID_ENTRY) ?: return@mapNotNull null
        SubtitleTrack(
            id = id,
            language = trackPropertyString(index, LANGUAGE_ENTRY),
            title = trackPropertyString(index, TITLE_ENTRY),
            isDefault = trackPropertyBoolean(index, DEFAULT_ENTRY) == true,
        )
    }

    override fun addExternalSubtitle(location: String) {
        if (externalSubtitleTrackId(location) != null) return
        MPVLib.command(arrayOf("sub-add", location))
    }

    override fun selectEmbeddedSubtitle(trackId: Int) {
        MPVLib.setPropertyInt(SID_PROPERTY, trackId)
    }

    /** 已加载过的外挂直接按轨道号选中，避免 sub-add 反复加出重份轨道。 */
    override fun selectExternalSubtitle(location: String) {
        val trackId = externalSubtitleTrackId(location)
        if (trackId == null) {
            MPVLib.command(arrayOf("sub-add", location, SUB_ADD_SELECT))
        } else {
            MPVLib.setPropertyInt(SID_PROPERTY, trackId)
        }
    }

    override fun disableSubtitles() {
        MPVLib.setPropertyString(SID_PROPERTY, SID_OFF)
    }

    override fun attachSurface(surface: Surface) {
        if (attachedSurface === surface && !videoOutputStopped) return
        if (videoOutputStopped) {
            MPVLib.setPropertyString("vo", MpvOptions.VIDEO_OUTPUT)
            videoOutputStopped = false
        }
        MPVLib.attachSurface(surface)
        attachedSurface = surface
        // 即使没有待播放的文件，也让 mpv 往这块 Surface 上画（字幕、OSD 等）
        MPVLib.setOptionString("force-window", "yes")
    }

    override fun detachSurface() {
        if (attachedSurface == null) return
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
        attachedSurface = null
        videoOutputStopped = true
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun release() {
        MPVLib.removeObserver(this)
        fileEvents.reset()
        readySignals.reset()
        MPVLib.destroy()
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
            MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG,
            MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG,
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART,
            MPVLib.MpvEvent.MPV_EVENT_SEEK,
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            -> listener?.onLoadActivity()
        }
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onFileLoaded()
            // 从这里开始，后面的 ready 才算 seek 之后的信号。
            MPVLib.MpvEvent.MPV_EVENT_SEEK -> readySignals.onSeekEvent()
            // 播放中优先等 PLAYBACK_RESTART（首帧已上屏）；暂停中只有 VIDEO_RECONFIG，
            // 它可能早于首帧上屏，交给控制层做短暂延迟。
            MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                if (fileEvents.isLoaded && hasVideoTrack && isPaused()) reportReady(fromVideoReconfig = true)
            }
            MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG -> {
                if (fileEvents.isLoaded && !hasVideoTrack) reportReady()
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> if (fileEvents.isLoaded) reportReady()
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> onEndFile()
        }
    }

    private fun onFileLoaded() {
        // 旧代际迟到的 FILE_LOADED 不能当成当前目标加载成功。
        if (!fileEvents.onFileLoaded(isTargetFile())) return
        hasVideoTrack = trackIndexes().any { trackPropertyString(it, TYPE_ENTRY) == VIDEO_TRACK_TYPE }
        listener?.onMediaLoaded()
        // 纯音频没有首帧可等；有视频时等 VIDEO_RECONFIG 或 PLAYBACK_RESTART。
        if (!hasVideoTrack) reportReady()
    }

    /** mpv 的 path 属性与本次目标不一致时，说明是旧文件迟到的加载事件。 */
    private fun isTargetFile(): Boolean =
        isTargetPath(targetUrl, MPVLib.getPropertyString(PATH_PROPERTY))

    private fun isPaused(): Boolean = MPVLib.getPropertyBoolean(PAUSE_PROPERTY) == true

    /** 替换、自然结束与失败由 [MpvFileEvents] 判定。 */
    private fun onEndFile() {
        val atEndByPosition = durationSeconds > 0 &&
            positionSeconds >= durationSeconds - EOF_TOLERANCE_SECONDS
        when (fileEvents.onEndFile(atEndByPosition)) {
            MpvFileEvents.End.Replaced -> Unit
            MpvFileEvents.End.Ended -> listener?.onPlaybackEnded()
            MpvFileEvents.End.Failed -> listener?.onPlaybackFailed()
        }
    }

    /**
     * 同一 seek 序号内，重配与重播各上报一次；seek 之后序号变化，两类信号都能再次上报。
     * 播放中的首帧以 PLAYBACK_RESTART 为准，暂停态则以 VIDEO_RECONFIG 加抓帧确认。
     */
    private fun reportReady(fromVideoReconfig: Boolean = false) {
        val serial = readySignals.acceptReady(fromVideoReconfig) ?: return
        listener?.onFirstFrameReady(fromVideoReconfig, targetUrl, serial)
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            WIDTH_PROPERTY -> videoWidth = value.toInt()
            HEIGHT_PROPERTY -> videoHeight = value.toInt()
            else -> return
        }
        listener?.onVideoSizeChanged(videoWidth, videoHeight)
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            TIME_POS_PROPERTY -> positionSeconds = value
            DURATION_PROPERTY -> durationSeconds = value
            else -> return
        }
        listener?.onPositionChanged(toMillis(positionSeconds), toMillis(durationSeconds))
    }

    override fun eventProperty(property: String, value: String) = Unit

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            PAUSE_PROPERTY -> listener?.onPauseChanged(value)
            EOF_REACHED_PROPERTY -> fileEvents.onEofChanged(value)
        }
    }

    /** mpv 在时长未知时上报 NaN，按 0 处理。 */
    private fun toMillis(seconds: Double): Long =
        if (seconds.isNaN() || seconds < 0) 0 else (seconds * 1000).toLong()

    private fun trackIndexes(): IntRange = 0 until (MPVLib.getPropertyInt(TRACK_COUNT_PROPERTY) ?: 0)

    private fun trackPropertyInt(index: Int, entry: String): Int? =
        MPVLib.getPropertyInt(trackProperty(index, entry))

    private fun trackPropertyString(index: Int, entry: String): String? =
        MPVLib.getPropertyString(trackProperty(index, entry))

    private fun trackPropertyBoolean(index: Int, entry: String): Boolean? =
        MPVLib.getPropertyBoolean(trackProperty(index, entry))

    private fun trackProperty(index: Int, entry: String): String = "track-list/$index/$entry"

    /** 已加载的外挂字幕文件的轨道号；没加载过时为 null。 */
    private fun externalSubtitleTrackId(location: String): Int? = trackIndexes().firstNotNullOfOrNull { index ->
        if (trackPropertyString(index, TYPE_ENTRY) != SUBTITLE_TRACK_TYPE) return@firstNotNullOfOrNull null
        if (trackPropertyBoolean(index, EXTERNAL_ENTRY) != true) return@firstNotNullOfOrNull null
        if (trackPropertyString(index, EXTERNAL_FILENAME_ENTRY) != location) return@firstNotNullOfOrNull null
        trackPropertyInt(index, ID_ENTRY)
    }

    private fun formatSeconds(millis: Long): String = String.format(Locale.US, "%.3f", millis / 1000.0)

    private companion object {
        const val PAUSE_PROPERTY = "pause"
        const val HWDEC_PROPERTY = "hwdec"
        const val TIME_POS_PROPERTY = "time-pos"
        const val DURATION_PROPERTY = "duration"
        const val WIDTH_PROPERTY = "width"
        const val HEIGHT_PROPERTY = "height"
        const val EOF_REACHED_PROPERTY = "eof-reached"
        const val PATH_PROPERTY = "path"
        const val SID_PROPERTY = "sid"
        const val TRACK_COUNT_PROPERTY = "track-list/count"
        const val TYPE_ENTRY = "type"
        const val ID_ENTRY = "id"
        const val LANGUAGE_ENTRY = "lang"
        const val TITLE_ENTRY = "title"
        const val EXTERNAL_ENTRY = "external"
        const val DEFAULT_ENTRY = "default"
        const val EXTERNAL_FILENAME_ENTRY = "external-filename"
        const val SUBTITLE_TRACK_TYPE = "sub"
        const val VIDEO_TRACK_TYPE = "video"
        const val SUB_ADD_SELECT = "select"

        /** 抓冻结画面的目标像素边长。 */
        const val GRAB_DIMENSION = 1920

        /** `sid` 取该值表示不显示字幕。 */
        const val SID_OFF = "no"

        /** 位置与时长差在这个范围内就算自然播完，兜底 eof-reached 未上报的情况。 */
        const val EOF_TOLERANCE_SECONDS = 1.0
    }
}

/** path 属性缺失时不阻断加载；存在且与目标不同才判定为旧代际事件。 */
internal fun isTargetPath(target: String?, path: String?): Boolean =
    target == null || path == null || path == target
