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

    /** 播放核心的错误日志；START_FILE 清空，END_FILE 失败时随回调带给控制层。 */
    private val errorLog = MpvErrorLogBridge { text -> listener?.onPlaybackFailed(text) }

    /** detachSurface 会把视频输出关掉，重新接上 Surface 前要恢复它。 */
    @Volatile
    private var videoOutputStopped = false

    /** 当前挂着的 Surface；重复挂载同一块时不重建渲染上下文。 */
    @Volatile
    private var attachedSurface: Surface? = null

    /** 退后台后重新挂上 Surface，等待播放位置再次前进以证明画面已输出。 */
    @Volatile
    private var awaitingSurfaceFrame = false

    /** 新加载的文件尚未看到播放位置前进。 */
    @Volatile
    private var awaitingLoadFrame = false

    /** 位置前进判定：seek 后先重建基线，避免队列里的旧位置冒充前进。 */
    private val positionGate = PositionProgressGate()

    @Volatile
    private var surfaceGenerationCounter = 0

    override val surfaceGeneration: Int get() = surfaceGenerationCounter

    /** 加载代际、结束判定与 ready 序号；同一临界区，控制层与事件线程都会调用。 */
    private val loadCoordinator = MpvLoadCoordinator()

    /** 当前文件是否有视频轨道；纯音频没有首帧，文件打开即可视为就绪。 */
    @Volatile
    private var hasVideoTrack = false

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
        MPVLib.addLogObserver(errorLog)
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

    override fun start(url: String, paused: Boolean, startPositionMillis: Long): Int {
        // 与日志回调共用同一把锁：新加载一开始就换代，不沿用上一轮文本。
        errorLog.start()
        val spec = loadCoordinator.start(url)
        // 错误日志在事件线程收到 START_FILE 时清空，避免上一轮排队日志混进本轮。
        positionSeconds = 0.0
        durationSeconds = 0.0
        hasVideoTrack = false
        awaitingLoadFrame = true
        positionGate.onStart(startPositionMillis)
        MPVLib.setPropertyBoolean(PAUSE_PROPERTY, paused)
        val command = mutableListOf("loadfile", url, "replace")
        if (startPositionMillis > 0) {
            command += "-1"
            command += "start=${formatSeconds(startPositionMillis)}"
        }
        MPVLib.command(command.toTypedArray())
        return spec.token
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
        loadCoordinator.stop()
        hasVideoTrack = false
        awaitingLoadFrame = false
        MPVLib.command(arrayOf("stop"))
    }

    /** 看门狗恢复前丢掉不会再有事件的死代际，避免恢复的 FILE_LOADED 配到旧加载。 */
    override fun discardInFlightLoads() {
        loadCoordinator.discardInFlight()
    }

    /**
     * seek 后 mpv 会再发 PLAYBACK_RESTART。序号在这里下发，
     * 但要等 MPV_EVENT_SEEK 到达后才用于标记 ready，迟到的旧事件仍带旧序号。
     */
    override fun seekTo(positionMillis: Long): Int {
        positionSeconds = positionMillis / 1000.0
        // 跳转本身不是播放推进；跳转后的首个位置事件只重建基线。
        positionGate.onSeek(positionMillis)
        val serial = loadCoordinator.onSeekIssued()
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
            // 先进入等待，再切回视频输出，避免首个重配事件先到而被漏掉。
            awaitingSurfaceFrame = true
            videoOutputStopped = false
            surfaceGenerationCounter++
            MPVLib.setPropertyString("vo", MpvOptions.VIDEO_OUTPUT)
        }
        attachedSurface = surface
        MPVLib.attachSurface(surface)
        // 即使没有待播放的文件，也让 mpv 往这块 Surface 上画（字幕、OSD 等）
        MPVLib.setOptionString("force-window", "yes")
    }

    override fun detachSurface() {
        if (attachedSurface == null) return
        awaitingSurfaceFrame = false
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
        attachedSurface = null
        videoOutputStopped = true
    }

    /** 重新接一次同一块 Surface 并重设视频输出，逼播放核心再出一帧；不改变播放位置。 */
    override fun refreshSurface(): Boolean {
        val surface = attachedSurface ?: return false
        if (videoOutputStopped) return false
        if (!loadCoordinator.isFileLoaded || !hasVideoTrack) return false
        awaitingSurfaceFrame = true
        surfaceGenerationCounter++
        MPVLib.setPropertyString("vo", "null")
        MPVLib.detachSurface()
        MPVLib.setPropertyString("vo", MpvOptions.VIDEO_OUTPUT)
        MPVLib.attachSurface(surface)
        MPVLib.setOptionString("force-window", "yes")
        return true
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun release() {
        MPVLib.removeObserver(this)
        MPVLib.removeLogObserver(errorLog)
        loadCoordinator.reset()
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
            // 在事件线程清空上一轮错误日志，避免已排队日志混进新加载。
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> errorLog.onStartFile()
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onFileLoaded()
            // 从这里开始，后面的 ready 才算 seek 之后的信号。
            MPVLib.MpvEvent.MPV_EVENT_SEEK -> loadCoordinator.onSeekEvent()
            // 播放中优先等 PLAYBACK_RESTART；暂停中只有 VIDEO_RECONFIG，
            // 它可能早于首帧上屏，控制层要等位置真正前进后才算就绪。
            MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                if (loadCoordinator.isFileLoaded && hasVideoTrack && isPaused()) {
                    reportReady(needsFrameConfirmation = true)
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG -> {
                if (loadCoordinator.isFileLoaded && !hasVideoTrack) reportReady()
            }
            // 暂停态的重播同样不能证明画面可显示；纯音频没有帧可等。
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> if (loadCoordinator.isFileLoaded) {
                reportReady(needsFrameConfirmation = isPaused() && hasVideoTrack)
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> onEndFile()
        }
    }

    private fun onFileLoaded() {
        // 先消化旧代际的结束事件并按 mpv 当前路径校验队首，迟到事件不能占掉新加载的代际。
        val spec = loadCoordinator.onFileLoaded(MPVLib.getPropertyString(PATH_PROPERTY)) ?: return
        hasVideoTrack = trackIndexes().any { trackPropertyString(it, TYPE_ENTRY) == VIDEO_TRACK_TYPE }
        listener?.onMediaLoaded(spec.url, spec.token, hasVideoTrack)
        // 纯音频没有首帧可等；有视频时等 VIDEO_RECONFIG 或 PLAYBACK_RESTART。
        if (!hasVideoTrack) reportReady()
    }

    private fun isPaused(): Boolean = MPVLib.getPropertyBoolean(PAUSE_PROPERTY) == true

    /** 替换与失败由 [MpvFileEvents] 判定；自然播完只由 eof-reached 上报。 */
    private fun onEndFile() {
        errorLog.onEndFile(loadCoordinator.onEndFile())
    }

    /**
     * 播放位置前进且 Surface 已挂时，才算新画面真正输出。
     * 暂停态没有位置前进，不会上报，冻结画面与后台缓存帧得以保留。
     */
    private fun notifyProgressFrame() {
        if (isPaused()) return
        // 纯音频没有画面可证明，首帧信号由 AUDIO_RECONFIG 处理。
        if (!hasVideoTrack) return
        if (attachedSurface == null || videoOutputStopped) return
        if (!awaitingLoadFrame && !awaitingSurfaceFrame) return
        val token = loadCoordinator.currentLoadedToken() ?: return
        awaitingLoadFrame = false
        awaitingSurfaceFrame = false
        listener?.onFramePresented(surfaceGenerationCounter, token)
    }

    /**
     * 同一 seek 序号内，需等待真正出帧与无需确认两类 ready 各只上报一次；
     * seek 之后序号变化，两类信号都能再次上报。
     */
    private fun reportReady(needsFrameConfirmation: Boolean = false) {
        // 先确认 ready 属于当前代际再占去重槽：旧代际不吞当前加载的信号。
        val ready = loadCoordinator.acceptReady(needsFrameConfirmation) ?: return
        listener?.onFirstFrameReady(
            needsFrameConfirmation,
            ready.spec.url,
            ready.serial,
            ready.spec.token,
        )
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
            TIME_POS_PROPERTY -> {
                positionSeconds = value
                if (positionGate.onPosition(toMillis(value))) notifyProgressFrame()
            }
            DURATION_PROPERTY -> durationSeconds = value
            else -> return
        }
        listener?.onPositionChanged(toMillis(positionSeconds), toMillis(durationSeconds))
    }

    override fun eventProperty(property: String, value: String) = Unit

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            PAUSE_PROPERTY -> listener?.onPauseChanged(value)
            EOF_REACHED_PROPERTY -> if (value) {
                // 迟到的旧文件 eof 可能在新文件加载后才送达：以当前属性值为准。
                if (MPVLib.getPropertyBoolean(EOF_REACHED_PROPERTY) != true) return
                loadCoordinator.onEofChanged(true)?.let { listener?.onPlaybackEnded(it) }
            } else {
                loadCoordinator.onEofChanged(false)
            }
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
    }
}

/**
 * END_FILE 失败时把当前错误日志文本交给控制层；替换与自然播完不通知。
 * 单独抽出，便于不依赖 JNI 验证 END_FILE -> onPlaybackFailed 的接线。
 */
internal fun dispatchPlaybackFailure(
    end: MpvFileEvents.End,
    errorText: () -> String?,
    onFailure: (String?) -> Unit,
) {
    if (end != MpvFileEvents.End.Failed) return
    onFailure(errorText())
}

/**
 * 收集 mpv 日志中的错误文本。保留首条根因与最近几条；
 * mpv 事件线程在 START_FILE 清空，END_FILE 失败时读取，访问加锁。
 */
internal class MpvErrorCollector(capacity: Int = 3) {

    private val capacity = capacity.coerceAtLeast(2)

    private val errors = ArrayDeque<String>()

    @Synchronized
    fun reset() {
        errors.clear()
    }

    @Synchronized
    fun onLog(prefix: String, level: Int, text: String) {
        // 只留 fatal / error；warning 及以下不是失败原因。
        if (level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) return
        val message = text.trim()
        if (message.isEmpty()) return
        if (errors.size >= capacity) {
            // 保留首条根因，淘汰紧随其后的旧条目，再放入最新一条。
            val first = errors.removeFirst()
            while (errors.size > capacity - 2) errors.removeFirst()
            errors.addFirst(first)
        }
        errors.addLast(if (prefix.isBlank()) message else "$prefix: $message")
    }

    @Synchronized
    fun lastErrorText(): String? = errors.joinToString(" | ").takeIf { it.isNotBlank() }
}

/**
 * 文件事件与日志桥的接线：start 与 START_FILE 在同一把锁下清空上一轮，
 * 日志条目带加载代际，END_FILE 只读本轮并在读取后 reset。
 * 单独抽出，便于不依赖 JNI 验证顺序。
 */
internal class MpvErrorLogBridge(
    private val onFailure: (String?) -> Unit,
) : MPVLib.LogObserver {

    private val lock = Any()
    private val errors = ArrayDeque<Pair<Int, String>>()
    private val capacity = 3
    private var generation = 0

    /** 新一次加载开始：同步换代并清空上一轮，避免沿用旧文本。 */
    fun start() {
        synchronized(lock) {
            generation++
            errors.clear()
        }
    }

    /** mpv 事件线程转发的日志；只留下错误级别并标记当前加载代际。 */
    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) return
        val message = text.trim()
        if (message.isEmpty()) return
        synchronized(lock) {
            if (errors.size >= capacity) {
                // 保留首条根因，淘汰紧随其后的旧条目，再放入最新一条。
                val first = errors.removeFirst()
                while (errors.size > capacity - 2) errors.removeFirst()
                errors.addFirst(first)
            }
            errors.addLast(generation to if (prefix.isBlank()) message else "$prefix: $message")
        }
    }

    /** START_FILE 也清空一次：旧文件结束到新文件开始之间可能还有排队日志。 */
    fun onStartFile() {
        synchronized(lock) {
            errors.clear()
        }
    }

    /** END_FILE 只读当前代际；无论成功失败，读完即 reset。 */
    fun onEndFile(end: MpvFileEvents.End) {
        val text = synchronized(lock) {
            val current = errors.filter { it.first == generation }.map { it.second }
            val value = current.joinToString(" | ").takeIf { it.isNotBlank() }
            errors.clear()
            if (end == MpvFileEvents.End.Failed) value else null
        }
        if (end == MpvFileEvents.End.Failed) onFailure(text)
    }
}

/**
 * 播放位置前进判定：新加载或 seek 后的首个位置事件只重建基线，
 * 避免上一个位置或队列里的旧大位置被误判为“前进”。线程由调用方保证。
 */
internal class PositionProgressGate {

    private var lastPositionMillis = 0L
    private var baselineTargetMillis = 0L
    private var rebuildBaseline = true
    private var dropAboveBaseline = false

    fun onStart(positionMillis: Long) {
        lastPositionMillis = positionMillis
        baselineTargetMillis = positionMillis
        rebuildBaseline = true
        dropAboveBaseline = false
    }

    fun onSeek(positionMillis: Long) {
        // 只有回退 seek 需要丢掉队列里更大的旧位置。
        dropAboveBaseline = positionMillis < lastPositionMillis
        lastPositionMillis = positionMillis
        baselineTargetMillis = positionMillis
        rebuildBaseline = true
    }

    /** 返回 true 表示位置确实前进，可作为画面已输出的证据。 */
    fun onPosition(positionMillis: Long): Boolean {
        if (rebuildBaseline) {
            if (!dropAboveBaseline || positionMillis <= baselineTargetMillis + BASELINE_TOLERANCE_MILLIS) {
                lastPositionMillis = positionMillis
                rebuildBaseline = false
            }
            return false
        }
        val advanced = positionMillis > lastPositionMillis &&
            positionMillis - lastPositionMillis <= MAX_ADVANCE_MILLIS
        lastPositionMillis = positionMillis
        return advanced
    }

    private companion object {
        /** seek 后允许作为新基线的目标误差。 */
        const val BASELINE_TOLERANCE_MILLIS = 500L

        /** 单次正常推进的上限；更大的跳变只重建基线。 */
        const val MAX_ADVANCE_MILLIS = 2_000L
    }
}

/** 只有目标与 mpv 当前 path 都存在且一致，才承认这次加载；其余都按旧代际事件丢弃。 */
internal fun isTargetPath(target: String?, path: String?): Boolean =
    target != null && path == target
