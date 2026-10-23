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

    /** 播放核心的错误日志；条目按加载令牌归属，END_FILE 失败时随该令牌交给控制层。 */
    private val errorLog = MpvErrorLogBridge { loadToken, text ->
        listener?.onPlaybackFailed(loadToken, text)
    }

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
        val spec = loadCoordinator.start(url)
        // 成功打开前的日志也要归到本次加载；START_FILE 会清掉这之前混入的上一轮排队日志。
        errorLog.onStartLoad(spec.token)
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
            // START_FILE 按命令顺序归属于队首加载；错误日志也在这里归到同一令牌。
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                val token = loadCoordinator.pendingLoadToken()
                listener?.onLoadActivity(LoadActivity.StartFile, token)
                errorLog.onStartFile(token)
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onFileLoaded()
            // 从这里开始，后面的 ready 才算 seek 之后的信号。
            MPVLib.MpvEvent.MPV_EVENT_SEEK -> {
                listener?.onLoadActivity(LoadActivity.Seek, loadCoordinator.currentLoadedToken())
                loadCoordinator.onSeekEvent()
            }
            // 播放中优先等 PLAYBACK_RESTART；暂停中只有 VIDEO_RECONFIG，
            // 它可能早于首帧上屏，控制层要等位置真正前进后才算就绪。
            MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                listener?.onLoadActivity(LoadActivity.VideoReconfig, loadCoordinator.currentLoadedToken())
                if (loadCoordinator.isFileLoaded && hasVideoTrack && isPaused()) {
                    reportReady(needsFrameConfirmation = true)
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG -> {
                listener?.onLoadActivity(LoadActivity.AudioReconfig, loadCoordinator.currentLoadedToken())
                if (loadCoordinator.isFileLoaded && !hasVideoTrack) reportReady()
            }
            // 暂停态的重播同样不能证明画面可显示；纯音频没有帧可等。
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                listener?.onLoadActivity(LoadActivity.PlaybackRestart, loadCoordinator.currentLoadedToken())
                if (loadCoordinator.isFileLoaded) {
                    reportReady(needsFrameConfirmation = isPaused() && hasVideoTrack)
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> onEndFile()
        }
    }

    private fun onFileLoaded() {
        // 先消化旧代际的结束事件并按 mpv 当前路径校验队首，迟到事件不能占掉新加载的代际。
        val spec = loadCoordinator.onFileLoaded(MPVLib.getPropertyString(PATH_PROPERTY)) ?: return
        // 只有接受的 FILE_LOADED 才算本轮加载活动；被拒绝的旧事件不能刷新无帧窗口。
        listener?.onLoadActivity(LoadActivity.FileLoaded, spec.token)
        hasVideoTrack = trackIndexes().any { trackPropertyString(it, TYPE_ENTRY) == VIDEO_TRACK_TYPE }
        listener?.onMediaLoaded(spec.url, spec.token, hasVideoTrack)
        // 纯音频没有首帧可等；有视频时等 VIDEO_RECONFIG 或 PLAYBACK_RESTART。
        if (!hasVideoTrack) reportReady()
    }

    private fun isPaused(): Boolean = MPVLib.getPropertyBoolean(PAUSE_PROPERTY) == true

    /** 替换与失败由 [MpvFileEvents] 判定；自然播完只由 eof-reached 上报。 */
    private fun onEndFile() {
        val end = loadCoordinator.onEndFile()
        listener?.onLoadActivity(LoadActivity.EndFile, end.spec?.token)
        errorLog.onEndFile(end)
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
 * 文件事件与日志桥的接线：日志条目按加载令牌独立归属。
 * start 把打开阶段的日志归到本次加载；START_FILE 清掉同一令牌在真正开始加载前的排队日志，
 * END_FILE 读取并清理该令牌的文本，替换与播完的结束不会动当前轮的文本。
 * 单独抽出，便于不依赖 JNI 验证顺序。
 */
internal class MpvErrorLogBridge(
    private val onFailure: (loadToken: Int, errorText: String?) -> Unit,
) : MPVLib.LogObserver {

    private data class Entry(val sequence: Long, val text: String)

    private val lock = Any()

    /** 每个加载令牌独立缓冲，旧令牌残留不会挤掉本轮首条根因。 */
    private val errors = mutableMapOf<Int, ArrayDeque<Entry>>()
    private val capacity = 3
    private var activeToken: Int? = null

    /** 最近一次 start 的令牌与当时序号；只由 start 前进，用于没有 START_FILE 时的失败兜底。 */
    private var startedToken: Int? = null
    private var startedAfterSequence = 0L
    private var startFileSeen = false
    private var nextSequence = 0L

    /** 新一次加载：后续日志归到该令牌，只清理该令牌的旧条目。 */
    fun onStartLoad(loadToken: Int) {
        synchronized(lock) {
            activeToken = loadToken
            startedToken = loadToken
            startedAfterSequence = nextSequence
            startFileSeen = false
            errors.keys.retainAll { it == loadToken }
            errors.remove(loadToken)
        }
    }

    /** mpv 事件线程转发的日志；只留下错误级别并按当前加载令牌归属。 */
    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) return
        val message = text.trim()
        if (message.isEmpty()) return
        synchronized(lock) {
            val token = activeToken ?: return
            val entries = errors.getOrPut(token) { ArrayDeque() }
            if (entries.size >= capacity) {
                // 保留首条根因，淘汰紧随其后的旧条目，再放入最新一条。
                val first = entries.removeFirst()
                while (entries.size > capacity - 2) entries.removeFirst()
                entries.addFirst(first)
            }
            entries.addLast(Entry(++nextSequence, if (prefix.isBlank()) message else "$prefix: $message"))
        }
    }

    /**
     * START_FILE 归属到队首加载：清掉同一令牌在开始加载前混入的上一轮排队日志。
     * 锚点只由 start 前进，迟到的旧 START_FILE 只切 activeToken，不回退兜底锚点。
     * [loadToken] 为 null 表示队列已清空，后续日志不再挂到旧令牌。
     */
    fun onStartFile(loadToken: Int?) {
        synchronized(lock) {
            activeToken = loadToken
            if (loadToken == null) return
            errors.remove(loadToken)
            // 锚点只允许由 start 前进；没有 start 时用第一次 START_FILE 建锚点。
            if (startedToken == null) {
                startedToken = loadToken
                startedAfterSequence = nextSequence
            }
            if (loadToken == startedToken) startFileSeen = true
        }
    }

    /** 读取并清理该 END_FILE 所属令牌的文本；只有失败才上报。 */
    fun onEndFile(end: LoadEnd) {
        val token = end.spec?.token
        val text = synchronized(lock) {
            if (token == null) {
                // 找不到加载的结束：丢掉非当前加载的残留，避免长期驻留。
                errors.keys.retainAll { it == startedToken }
                return@synchronized null
            }
            // 旧文件卸载日志可能已按 start 后的新令牌入桶：START_FILE 前把它们清掉，
            // 否则新加载无 START_FILE 失败时会把旧卸载文本当成本轮错误。
            if (end.end == MpvFileEvents.End.Replaced && !startFileSeen && token != startedToken) {
                errors.values.forEach { entries ->
                    entries.removeAll { it.sequence > startedAfterSequence }
                }
            }
            val entries = errors[token]
            var value = entries?.joinToString(" | ") { it.text }?.takeIf { it.isNotBlank() }
            if (value == null && token == startedToken) {
                // 本令牌没有条目：回退到本次 start 之后尚未消费的条目，覆盖没有 START_FILE 的打开失败。
                val fallback = errors.values.asSequence()
                    .flatten()
                    .filter { it.sequence > startedAfterSequence }
                    .sortedBy { it.sequence }
                    .map { it.text }
                    .toList()
                value = fallback.joinToString(" | ").takeIf { it.isNotBlank() }
                if (value != null) {
                    errors.values.forEach { list -> list.removeAll { it.sequence > startedAfterSequence } }
                }
            }
            errors.remove(token)
            errors.entries.removeAll { it.value.isEmpty() }
            value
        }
        if (end.end == MpvFileEvents.End.Failed && token != null) onFailure(token, text)
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
