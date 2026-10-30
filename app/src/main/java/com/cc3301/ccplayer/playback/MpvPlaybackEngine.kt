package com.cc3301.ccplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import `is`.xyz.mpv.MPVLib
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 用 mpv-android 官方 libmpv 构建产物实现的播放引擎。
 *
 * 渲染入口是 [MPVLib.attachSurface]：libmpv 的 render API 直接把画面画到视图的 Surface 上，
 * 相当于 mpv-android 示例工程里 MPVView 承担的渲染职责。
 */
class MpvPlaybackEngine(
    private val context: Context,
    private val cacheDir: String,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
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

    /** 挂载后等待播放位置再次前进，作为画面已重新输出到 Surface 的信号。 */
    @Volatile
    private var awaitingSurfaceFrame = false

    /** 新加载的文件尚未看到播放位置前进。 */
    @Volatile
    private var awaitingLoadFrame = false

    /** 位置前进判定：seek 后先重建基线，避免队列里的旧位置冒充前进。 */
    private val positionGate = PositionProgressGate()

    /** 加载代际、结束判定与 ready 序号；同一临界区，控制层与事件线程都会调用。 */
    private val loadCoordinator = MpvLoadCoordinator()

    /** 当前文件是否有视频轨道；纯音频没有首帧，文件打开即可视为就绪。 */
    @Volatile
    private var hasVideoTrack = false

    /** seek 队列：恒为「1 执行 + 1 待执行」，在途超时也归它判定。 */
    private val seekQueue = MpvSeekQueue(
        post = ::postSeekCommand,
        onExecuted = { seekId, targetMillis -> listener?.onSeekExecuted(seekId, targetMillis) },
        armTimeout = ::armSeekTimeout,
        cancelTimeout = ::cancelSeekTimeout,
    )

    /** 在途 seek 的超时计时器：回执丢失也能放开队列并照常回报。 */
    private val seekTimeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mpv-seek-timeout").apply { isDaemon = true }
    }

    /** 下发 seek 命令的唯一线程：计时器线程只投递「这一跳已完成」，mpv 命令一律在这里执行。 */
    private val seekCommandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-seek-command").apply { isDaemon = true }
    }

    /** 计时器的取消与重装、以及释放后不再受理，都在同一把锁内。 */
    private val seekTimeoutLock = Any()

    private var seekTimeoutFuture: ScheduledFuture<*>? = null

    /** 已释放：此后不再排计时器、不再投递命令，避免抛 RejectedExecutionException。 */
    @Volatile
    private var released = false

    @Volatile
    private var positionSeconds = 0.0

    @Volatile
    private var durationSeconds = 0.0

    /** mpv 事件线程写，抓帧线程读；冻结画面按这对尺寸裁切。 */
    @Volatile
    private var videoWidth = 0

    @Volatile
    private var videoHeight = 0

    /** seek 归属状态始终维护；开关只决定是否读属性与拼日志。 */
    private val seekDiagnostics = MpvSeekDiagnostics(
        diagnostics = diagnostics,
        snapshot = ::audioDebugSnapshot,
        durationSeconds = { durationSeconds },
    )

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
        seekDiagnostics.reset()
        seekQueue.reset()
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

    /**
     * mpv 的缩略图接口直接取当前视频帧；没有画面时返回 null。
     * 接口只给中心正方形，按当前视频宽高比裁出中心区域，冻结画面才不会显示成正方形。
     */
    override fun grabFrame(): PlaybackEngine.Frame? {
        val bitmap = runCatching { MPVLib.grabThumbnail(GRAB_DIMENSION) }.getOrNull() ?: return null
        val crop = freezeFrameCrop(bitmap.width, bitmap.height, videoWidth, videoHeight)
        val cropped = if (crop.width == bitmap.width && crop.height == bitmap.height) {
            bitmap
        } else {
            runCatching { Bitmap.createBitmap(bitmap, crop.x, crop.y, crop.width, crop.height) }
                .getOrNull() ?: bitmap
        }
        return PlaybackEngine.Frame(cropped)
    }

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
        seekDiagnostics.reset()
        seekQueue.reset()
        MPVLib.command(arrayOf("stop"))
    }

    /** 看门狗恢复前丢掉不会再有事件的死代际，避免恢复的 FILE_LOADED 配到旧加载。 */
    override fun discardInFlightLoads() {
        loadCoordinator.discardInFlight()
    }

    /**
     * seek 后 mpv 会再发 PLAYBACK_RESTART：命令序号在入队时预留、真正下发时才进待认队列，
     * ready 带回的序号只结算「已下发且序号相等」的那一跳。
     * 队列归引擎所有：在途只覆盖待执行目标，执行完（回执或超时）补发。
     * 返回的 seekId 在这里分配，不随后续加载归零，落定靠它认这一跳。
     */
    override fun seekTo(positionMillis: Long): PlaybackEngine.SeekRequest {
        val seekId = nextSeekId.incrementAndGet()
        val serial = loadCoordinator.reserveSeekSerial()
        seekQueue.seek(seekId, serial, positionMillis)
        return PlaybackEngine.SeekRequest(seekId, serial)
    }

    /** 把这一跳交给命令线程；此时还没有碰 mpv。 */
    private fun postSeekCommand(seekId: Long, targetMillis: Long) {
        synchronized(seekTimeoutLock) {
            if (released) return
            seekCommandExecutor.execute { issueSeekCommand(seekId, targetMillis) }
        }
    }

    /**
     * 命令线程上真正下发一次 seek：这一跳已不是当前在途就丢弃，迟到任务不打到新文件上。
     * 队列锁内只登记归属并下发命令；属性读取与诊断写盘在锁外做。
     */
    private fun issueSeekCommand(seekId: Long, positionMillis: Long) {
        var registered: MpvSeekDiagnostics.PendingSeek? = null
        try {
            val serial = seekQueue.issueIfCurrent(seekId) { entrySerial ->
                positionSeconds = positionMillis / 1000.0
                // 跳转本身不是播放推进；跳转后的首个位置事件只重建基线。
                positionGate.onSeek(positionMillis)
                loadCoordinator.onSeekIssued(entrySerial)
                registered = seekDiagnostics.register(positionMillis, entrySerial)
                MPVLib.command(arrayOf("seek", formatSeconds(positionMillis), "absolute"))
            } ?: return
            seekDiagnostics.logIssued(positionMillis, serial)
        } catch (failure: Throwable) {
            registered?.let { seekDiagnostics.discard(it, failure) }
            throw failure
        }
    }

    /** 给在途这一跳重新计时：到点只把「这一跳已完成」投回命令线程，不在计时器线程下发命令。 */
    private fun armSeekTimeout(onTimeout: () -> Unit) {
        synchronized(seekTimeoutLock) {
            if (released) return
            seekTimeoutFuture?.cancel(false)
            seekTimeoutFuture = seekTimeoutExecutor.schedule(
                { postTimeout(onTimeout) },
                SEEK_IN_FLIGHT_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** 把一个到期任务从计时器线程交回命令线程执行。 */
    private fun postTimeout(task: () -> Unit) {
        synchronized(seekTimeoutLock) {
            if (released) return
            seekCommandExecutor.execute(task)
        }
    }

    private fun cancelSeekTimeout() {
        synchronized(seekTimeoutLock) {
            seekTimeoutFuture?.cancel(false)
            seekTimeoutFuture = null
        }
    }

    /** 用 mpv 的 lavfi 桥接 FFmpeg 的 afade：淡入从当前播放位置起算，只作用于音频链。 */
    override fun fadeInAudio(durationMillis: Long) {
        val startMillis = toMillis(positionSeconds)
        val fade = "afade=t=in:st=${formatSeconds(startMillis)}:d=${formatSeconds(durationMillis)}"
        dispatchFadeIn(
            diagnostics = diagnostics,
            startMillis = startMillis,
            durationMillis = durationMillis,
            command = { MPVLib.command(arrayOf("af", "set", "lavfi=[$fade]")) },
        ) { audioDebugSnapshot() }
    }

    /** 预览 seek 期间直接静音音频输出，不改变音量。 */
    override fun setAudioMuted(muted: Boolean) {
        MPVLib.setPropertyBoolean(MUTE_PROPERTY, muted)
    }

    /** 读取排查丢声所需的播放核心状态；任何属性读取失败都只记为空值。 */
    private fun audioDebugSnapshot(): AudioDebugSnapshot = AudioDebugSnapshot(
        paused = readMpvProperty { MPVLib.getPropertyBoolean(PAUSE_PROPERTY) },
        audioOutput = readMpvProperty { MPVLib.getPropertyString(AO_PROPERTY) },
        audioPtsSeconds = readMpvProperty { MPVLib.getPropertyDouble(AUDIO_PTS_PROPERTY) }?.finiteOrNull(),
        timePosSeconds = readMpvProperty { MPVLib.getPropertyDouble(TIME_POS_PROPERTY) }?.finiteOrNull(),
        eofReached = readMpvProperty { MPVLib.getPropertyBoolean(EOF_REACHED_PROPERTY) },
        audioDeviceConfig = readMpvProperty { MPVLib.getPropertyString(AUDIO_DEVICE_PROPERTY) },
        currentAudioOutput = readMpvProperty { MPVLib.getPropertyString(CURRENT_AO_PROPERTY) },
        volume = readMpvProperty { MPVLib.getPropertyDouble(VOLUME_PROPERTY) }?.finiteOrNull(),
        muted = readMpvProperty { MPVLib.getPropertyBoolean(MUTE_PROPERTY) },
        audioTrack = readMpvProperty { MPVLib.getPropertyString(AID_PROPERTY) },
    )

    private inline fun <T> readMpvProperty(block: () -> T?): T? = runCatching { block() }.getOrNull()

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
        if (attachedSurface === surface) return
        attachedSurface = surface
        MPVLib.attachSurface(surface)
    }

    /**
     * 挂载、设尺寸之后才开窗口并恢复视频输出：视频输出初始化时就能拿到 Surface 与尺寸，
     * 暂停态没有后续解码推进也能自行重绘一帧。
     */
    override fun startVideoOutput() {
        if (attachedSurface == null) return
        videoOutputStopped = false
        awaitingSurfaceFrame = true
        // 即使没有待播放的文件，也让 mpv 往这块 Surface 上画（字幕、OSD 等）
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setPropertyString("vo", MpvOptions.VIDEO_OUTPUT)
    }

    /** 只翻转视频输出：不 seek、不改播放位置，交给播放核心按当前帧重建输出。 */
    override fun flipVideoOutput() {
        if (attachedSurface == null || videoOutputStopped) return
        awaitingSurfaceFrame = true
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("vo", MpvOptions.VIDEO_OUTPUT)
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

    override fun setSurfaceSize(width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun release() {
        MPVLib.removeObserver(this)
        MPVLib.removeLogObserver(errorLog)
        // 先挡住新的排程与投递再关线程：释放后到点的计时器不能再排任务。
        synchronized(seekTimeoutLock) {
            released = true
            seekTimeoutFuture?.cancel(false)
            seekTimeoutFuture = null
        }
        seekQueue.reset()
        seekTimeoutExecutor.shutdownNow()
        seekCommandExecutor.shutdownNow()
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
                seekDiagnostics.onSeekEvent()
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
                // 音频链在重播后才完成重建，这里补记 seek 后的第二个快照。
                seekDiagnostics.onPlaybackRestart()
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
        listener?.onFramePresented(token)
    }

    /**
     * 同一 seek 序号内，需等待真正出帧与无需确认两类 ready 各只上报一次；
     * seek 之后序号变化，两类信号都能再次上报。
     */
    private fun reportReady(needsFrameConfirmation: Boolean = false) {
        // 先确认 ready 属于当前代际再占去重槽：旧代际不吞当前加载的信号。
        val ready = loadCoordinator.acceptReady(needsFrameConfirmation) ?: return
        // 只结算已下发且序号相等的那一跳：命令还没下发，或这一跳已被超时结算，都不在这里动队列。
        seekQueue.onReceipt(ready.serial)
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
        /** 在途 seek 的回执超时：超时按执行完处理并补发下一条，避免一段手势停在第一帧。 */
        const val SEEK_IN_FLIGHT_TIMEOUT_MILLIS = 3_000L

        /** seek 身份分配器：进程级单调，加载与停止都不重置，seekId 因此永不复用。 */
        val nextSeekId = AtomicLong(0)

        const val PAUSE_PROPERTY = "pause"
        const val HWDEC_PROPERTY = "hwdec"
        const val TIME_POS_PROPERTY = "time-pos"
        const val DURATION_PROPERTY = "duration"
        const val AO_PROPERTY = "ao"
        const val AUDIO_PTS_PROPERTY = "audio-pts"
        const val AUDIO_DEVICE_PROPERTY = "audio-device"
        const val CURRENT_AO_PROPERTY = "current-ao"
        const val VOLUME_PROPERTY = "volume"
        const val MUTE_PROPERTY = "mute"
        const val AID_PROPERTY = "aid"
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

/**
 * seek 归属状态：下发前在锁内登记，SEEK 事件按 FIFO 消费队首。
 * 归属只由队列决定；诊断开关只决定是否读属性与写日志，不参与状态维护。
 */
internal class MpvSeekDiagnostics(
    private val diagnostics: DiagnosticsLog,
    private val snapshot: () -> AudioDebugSnapshot,
    private val durationSeconds: () -> Double,
) {

    /** 已下发、等 MPV_EVENT_SEEK 消费的一跳；[previous] 用于命令失败时回退归属。 */
    class PendingSeek(val targetMillis: Long, val serial: Int, val previous: PendingSeek?)

    private val lock = Any()
    private val pending = ArrayDeque<PendingSeek>()
    private var lastIssued: PendingSeek? = null
    private var restartLoggedForSerial: Int? = null

    fun reset() {
        synchronized(lock) {
            pending.clear()
            lastIssued = null
            restartLoggedForSerial = null
        }
    }

    /** 锁内只登记归属：SEEK 事件按登记顺序消费，命令还没回来就已经能对上。 */
    fun register(targetMillis: Long, serial: Int): PendingSeek {
        synchronized(lock) {
            val seek = PendingSeek(targetMillis, serial, lastIssued)
            pending.addLast(seek)
            lastIssued = seek
            return seek
        }
    }

    /** 锁外：读属性并写下发前的诊断行。 */
    fun logIssued(targetMillis: Long, serial: Int) {
        if (!diagnostics.enabled) return
        runCatching {
            diagnostics.log {
                seekDebugLine("seek 前", targetMillis, serial, currentRemaining(targetMillis), snapshot())
            }
        }
    }

    /** 锁外：命令异常时按引用回滚登记并记录失败；已被 SEEK 事件消费时不回滚。 */
    fun discard(seek: PendingSeek, failure: Throwable) {
        synchronized(lock) {
            val index = pending.indexOfFirst { it === seek }
            val removed = index >= 0
            if (removed) pending.removeAt(index)
            // 已被 SEEK 事件消费时不能回退，否则其 restart 会归到旧目标。
            if (removed && lastIssued === seek) lastIssued = seek.previous
        }
        if (!diagnostics.enabled) return
        runCatching {
            diagnostics.log {
                "seek 下发失败: 目标=${seek.targetMillis}ms 序号=${seek.serial} " +
                    "错误=${failure.javaClass.simpleName}: ${failure.message ?: "-"}"
            }
        }
    }

    fun onSeekEvent() {
        // 与 MpvReadySignals 同规则：每个事件消费队首；剩余排队数写进行内。
        val consumed = synchronized(lock) {
            pending.removeFirstOrNull()?.let { it to pending.size }
        }
        if (!diagnostics.enabled) return
        runCatching {
            diagnostics.log {
                if (consumed == null) {
                    "seek 后(SEEK): 无对应目标 ${snapshot().debugText()}"
                } else {
                    seekDebugLine(
                        "seek 后(SEEK)",
                        consumed.first.targetMillis,
                        consumed.first.serial,
                        currentRemaining(consumed.first.targetMillis),
                        snapshot(),
                        queuedCount = consumed.second,
                    )
                }
            }
        }
    }

    fun onPlaybackRestart() {
        // 关闭期间不写去重状态，重新开启后仍能记录该序号的 restart 行。
        if (!diagnostics.enabled) return
        val seek = synchronized(lock) {
            val current = lastIssued ?: return
            if (restartLoggedForSerial == current.serial) return
            restartLoggedForSerial = current.serial
            current
        }
        runCatching {
            diagnostics.log {
                seekDebugLine(
                    "seek 后(PLAYBACK_RESTART)",
                    seek.targetMillis,
                    seek.serial,
                    currentRemaining(seek.targetMillis),
                    snapshot(),
                )
            }
        }
    }

    /** 写入侧按当时 duration 重算，避免时长晚到时永远记成未知。 */
    private fun currentRemaining(targetMillis: Long): Long? =
        remainingToEndMillis(targetMillis, durationSeconds())
}

/** 淡入下发与日志：日志失败不得覆盖命令原异常；关闭时不建日志 lambda、不读属性。 */
internal inline fun dispatchFadeIn(
    diagnostics: DiagnosticsLog,
    startMillis: Long,
    durationMillis: Long,
    command: () -> Unit,
    crossinline snapshot: () -> AudioDebugSnapshot,
) {
    var issued = false
    try {
        command()
        issued = true
    } finally {
        if (diagnostics.enabled) {
            runCatching {
                diagnostics.log { fadeDebugLine(startMillis, durationMillis, issued, snapshot()) }
            }
        }
    }
}

/** seek 前后的播放核心音频状态；属性不可用时留空。 */
internal data class AudioDebugSnapshot(
    val paused: Boolean? = null,
    val audioOutput: String? = null,
    val audioPtsSeconds: Double? = null,
    val timePosSeconds: Double? = null,
    val eofReached: Boolean? = null,
    /** `audio-device` 是配置值（默认 auto），不是实际输出设备。 */
    val audioDeviceConfig: String? = null,
    /** `current-ao` 是当前实际音频输出链。 */
    val currentAudioOutput: String? = null,
    val volume: Double? = null,
    val muted: Boolean? = null,
    val audioTrack: String? = null,
)

internal fun AudioDebugSnapshot.debugText(): String = buildString {
    append("pause=").append(flagOrDash(paused))
    append(" ao=").append(textOrDash(audioOutput))
    append(" audio-pts=").append(secondsOrDash(audioPtsSeconds))
    append(" time-pos=").append(secondsOrDash(timePosSeconds))
    append(" eof-reached=").append(flagOrDash(eofReached))
    append(" 配置设备=").append(textOrDash(audioDeviceConfig))
    append(" 当前ao=").append(textOrDash(currentAudioOutput))
    append(" volume=").append(secondsOrDash(volume))
    append(" muted=").append(flagOrDash(muted))
    append(" aid=").append(textOrDash(audioTrack))
}

/** seek 目标、序号与剩余时长；剩余不足 [SEEK_NEAR_END_MILLIS] 视为落在文件末尾附近。 */
internal fun seekDebugLine(
    prefix: String,
    targetMillis: Long,
    serial: Int,
    remainingMillis: Long?,
    snapshot: AudioDebugSnapshot,
    queuedCount: Int? = null,
): String = buildString {
    append(prefix).append(": 目标=").append(targetMillis).append("ms")
    append(" 序号=").append(serial)
    append(" 剩余=").append(remainingMillis?.let { "${it}ms" } ?: "-")
    append(" 近末尾=").append(nearEndFlag(remainingMillis))
    if (queuedCount != null) append(" 排队数=").append(queuedCount)
    append(' ').append(snapshot.debugText())
}

/** 时长未知时不做末尾判断。 */
internal fun remainingToEndMillis(targetMillis: Long, durationSeconds: Double): Long? {
    if (!durationSeconds.isFinite() || durationSeconds <= 0) return null
    return ((durationSeconds * 1000).toLong() - targetMillis).coerceAtLeast(0)
}

internal fun nearEndFlag(remainingMillis: Long?): String = when {
    remainingMillis == null -> "-"
    remainingMillis <= SEEK_NEAR_END_MILLIS -> "yes(≤5s)"
    else -> "no(>5s)"
}

/** 记录实际下发的 afade 起点与起始增益；当前音量来自 mpv 的 volume 属性。 */
internal fun fadeDebugLine(
    startMillis: Long,
    durationMillis: Long,
    issued: Boolean,
    snapshot: AudioDebugSnapshot,
): String = "淡入: 下发=${flagOrDash(issued)} 起始位置=${startMillis}ms " +
    "afade st=${secondsFromMillis(startMillis)}s 起始增益=0.000 时长=${durationMillis}ms " +
    "当前音量=${secondsOrDash(snapshot.volume)} 静音=${flagOrDash(snapshot.muted)}"

private fun secondsOrDash(value: Double?): String =
    value?.let { String.format(Locale.US, "%.3f", it) } ?: "-"

private fun secondsFromMillis(millis: Long): String =
    String.format(Locale.US, "%.3f", millis / 1000.0)

private fun flagOrDash(value: Boolean?): String = when (value) {
    null -> "-"
    true -> "yes"
    false -> "no"
}

private fun textOrDash(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "-"

private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

private const val SEEK_NEAR_END_MILLIS = 5_000L
