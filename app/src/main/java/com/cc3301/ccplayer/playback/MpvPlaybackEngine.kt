package com.cc3301.ccplayer.playback

import android.content.Context
import android.view.Surface
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import `is`.xyz.mpv.MPVLib
import java.util.Locale
import java.util.concurrent.CountDownLatch
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

    /** 挂载两态：会话意图与真正挂上的 Surface 在同一把锁里迁移。 */
    private val surfaceState = SurfaceMountState()

    /** 挂载后等待播放位置再次前进，作为画面已重新输出到 Surface 的信号。 */
    @Volatile
    private var awaitingSurfaceFrame = false

    /** 刷新序号 3 秒内没有等到 SEEK 事件时的丢弃计时。 */
    private var refreshTimeoutFuture: ScheduledFuture<*>? = null

    /** 新加载的文件尚未看到播放位置前进。 */
    @Volatile
    private var awaitingLoadFrame = false

    /** 视频输出重建后等音频重新出声的探针；跟随诊断开关，出声、无音频或超时后收摊。 */
    @Volatile
    private var audioResumeWatch: AudioResumeWatch? = null

    /** 探针起算时刻：视频输出重建完成的那一刻。 */
    @Volatile
    private var audioResumeArmedAtNanos = 0L

    /** 探针、轮询任务与它们的取消都在同一把锁内。 */
    private val audioResumeLock = Any()

    private var audioResumeFuture: ScheduledFuture<*>? = null

    /** 探针自己的轮询线程：不依赖播放位置事件，暂停态也能按时收摊。 */
    private val audioResumeExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mpv-audio-resume").apply { isDaemon = true }
    }

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

    /**
     * 视频输出属性的唯一线程：设置 vo 会重建整条输出并阻塞到内部完成，不能占用主线程。
     * 单线程保证多次开输出、翻转按调用顺序执行。
     */
    private val videoOutputExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-video-output").apply { isDaemon = true }
    }

    /**
     * 挂载超时的计时线程：排队挂载超时后按失败收尾。
     * 与释放收尾一样不能占用调用线程，主线程只负责发起。
     */
    private val surfaceTimeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mpv-surface-timeout").apply { isDaemon = true }
    }

    /** 视频输出调用都在这一条线程上按投递顺序执行，调用线程只投递。 */
    private val videoOutputQueue = VideoOutputQueue(
        execute = { task -> videoOutputExecutor.execute { task() } },
        schedule = { delayMillis, task ->
            runCatching {
                surfaceTimeoutExecutor.schedule({ task() }, delayMillis, TimeUnit.MILLISECONDS)
            }.isSuccess
        },
        onDiagnostic = { message -> diagnostics.log { message } },
    )

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

    /** mpv 事件线程写；视频区按这对尺寸解析比例。 */
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
        // 上一次释放的销毁在后台收尾：等到上限仍没结束就放弃本次创建。
        if (!nativeCore.beginCreate(NATIVE_CREATE_WAIT_MILLIS)) {
            diagnostics.log("创建: 上一次销毁仍在进行，放弃本次创建（等下一次服务重建）")
            return
        }
        createNativeCore()
    }

    private fun createNativeCore() {
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
        // 音频输出链变化常驻观测：切后台听感异常时靠它判断是否重建过 AO。
        MPVLib.observeProperty(AO_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_STRING)
        MPVLib.observeProperty(CURRENT_AO_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    override fun setListener(listener: PlaybackEngine.Listener) {
        this.listener = listener
    }

    override fun start(url: String, paused: Boolean, startPositionMillis: Long): Int {
        discardRefreshSeek()
        val spec = loadCoordinator.start(url)
        // 成功打开前的日志也要归到本次加载；START_FILE 会清掉这之前混入的上一轮排队日志。
        errorLog.onStartLoad(spec.token)
        positionSeconds = 0.0
        durationSeconds = 0.0
        hasVideoTrack = false
        awaitingLoadFrame = true
        // 新文件的时间轴从头开始：上一轮重建的出声探针作废，不能拿新文件的位置充数。
        endAudioResume(null)
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

    override fun pause() {
        MPVLib.setPropertyBoolean(PAUSE_PROPERTY, true)
    }

    override fun resume() {
        MPVLib.setPropertyBoolean(PAUSE_PROPERTY, false)
    }

    override fun stop() {
        // 停止产生的 END_FILE 由待吞状态消化，不能当成播放结束或失败。
        discardRefreshSeek()
        loadCoordinator.stop()
        hasVideoTrack = false
        awaitingLoadFrame = false
        seekDiagnostics.reset()
        seekQueue.reset()
        MPVLib.command(arrayOf("stop"))
    }

    /** 加载看护恢复前丢掉不会再有事件的死代际，避免恢复的 FILE_LOADED 配到旧加载。 */
    override fun discardInFlightLoads() {
        discardRefreshSeek()
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

    /**
     * 预览跳转：在途那一跳就地改成新目标并按新序号重新下发，旧序号的回执被丢弃，队列里只留当前最后目标。
     * 没有可改写的预览在途时按新的一跳入队。
     */
    override fun previewSeekTo(positionMillis: Long): PlaybackEngine.SeekRequest {
        seekQueue.retarget(positionMillis)?.let { return PlaybackEngine.SeekRequest(it.seekId, it.serial) }
        val seekId = nextSeekId.incrementAndGet()
        val serial = loadCoordinator.reserveSeekSerial()
        seekQueue.seek(seekId, serial, positionMillis, preview = true)
        return PlaybackEngine.SeekRequest(seekId, serial)
    }

    /** 把这一跳交给命令线程；此时还没有碰 mpv。 */
    private fun postSeekCommand(seekId: Long) {
        synchronized(seekTimeoutLock) {
            if (released) return
            seekCommandExecutor.execute { issueSeekCommand(seekId) }
        }
    }

    /**
     * 命令线程上真正下发一次 seek：这一跳已不是当前在途就丢弃，迟到任务不打到新文件上。
     * 队列锁内只登记归属并下发命令；诊断属性读取与写盘放在 [seekTimeoutLock] 内与释放互斥。
     * 目标取队列里的当前值：预览改写后按新序号重新下发，旧序号的 ready 不再结算这一跳。
     * 每条真正下发的命令都占一个序号；改写后的再次下发替换旧序号的登记，SEEK 事件按登记顺序消费。
     * 命令失败时队列已按失败结清这一跳并补发待执行，这里只收回诊断归属，不再向上抛。
     */
    private fun issueSeekCommand(seekId: Long) {
        var registered: MpvSeekDiagnostics.PendingSeek? = null
        var issued: Pair<Long, Int>? = null
        try {
            seekQueue.issueIfCurrent(seekId) { currentSerial, targetMillis, firstIssue ->
                // 改写后的再次下发换新序号：旧序号的回执因此不会结算这一跳。
                val serial = if (firstIssue) currentSerial else loadCoordinator.reserveSeekSerial()
                positionSeconds = targetMillis / 1000.0
                // 跳转本身不是播放推进；跳转后的首个位置事件只重建基线。
                positionGate.onSeek(targetMillis)
                loadCoordinator.onSeekStarted()
                if (firstIssue) {
                    loadCoordinator.onSeekIssued(serial)
                } else {
                    // 改写后的再次下发替换旧序号的登记：被替换的命令可能不再产生 SEEK 事件。
                    loadCoordinator.onSeekReissued(currentSerial, serial)
                }
                registered = seekDiagnostics.register(targetMillis, serial)
                issued = targetMillis to serial
                MPVLib.command(arrayOf("seek", formatSeconds(targetMillis), "absolute"))
                serial
            } ?: return
            issued?.let { (targetMillis, serial) -> logSeekIssued(targetMillis, serial) }
        } catch (failure: Throwable) {
            registered?.let { seekDiagnostics.discard(it, failure) }
        }
    }

    /**
     * 读属性写诊断行：与释放共用 [seekTimeoutLock]，锁内复查 [released] 后不再读已销毁的播放核心。
     * 诊断关闭时不取锁、不读属性。
     */
    private fun logSeekIssued(targetMillis: Long, serial: Int) {
        if (!diagnostics.enabled) return
        synchronized(seekTimeoutLock) {
            if (released) return
            seekDiagnostics.logIssued(targetMillis, serial)
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

    /** 按百分比设置音频输出音量；用于音频焦点暂降与恢复。 */
    override fun setVolumePercent(percent: Int) {
        MPVLib.setPropertyDouble(VOLUME_PROPERTY, percent.coerceIn(0, 100).toDouble())
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

    /**
     * 挂载 Surface：排队中的分离可能还没跑完，摘挂载会落在刚挂上的这一块之后。
     * 队列没有在途任务时当场挂；有在途任务就把这次挂载排到分离之后，调用线程不等待。
     * 排队的挂载超过上限还没执行时回滚挂载意图，迟到的任务不再挂载。
     */
    override fun attachSurface(surface: Surface) {
        if (released) return
        if (!surfaceState.require(surface)) return
        videoOutputQueue.attach(
            stillCurrent = { !released && surfaceState.isIntended(surface) },
            attach = {
                MPVLib.attachSurface(surface)
                surfaceState.onMounted(surface)
            },
            onSkipped = {
                // 这次挂载不会发生：回滚意图，后续不再对着它开输出。
                if (surfaceState.onSkipped(surface)) {
                    videoOutputStopped = true
                    diagnostics.log("挂载: 这次挂载没执行（Surface 已换、已释放或视频输出线程已关）")
                }
            },
            timeoutMillis = SURFACE_ATTACH_TIMEOUT_MILLIS,
            onTimeout = {
                // 视频输出线程卡住时挂载永远不会执行：回滚意图，后续不再对着它开输出。
                if (surfaceState.onTimedOut(surface)) {
                    awaitingSurfaceFrame = false
                    videoOutputStopped = true
                    diagnostics.log("挂载: 视频输出线程 ${SURFACE_ATTACH_TIMEOUT_MILLIS}ms 内没有执行，放弃这次挂载")
                }
            },
        )
    }

    /**
     * 挂载、设尺寸之后才开窗口并恢复视频输出：视频输出初始化时就能拿到 Surface 与尺寸。
     * 暂停态没有后续解码推进，输出重建本身不保证重绘当前帧，由控制层随后请求一次精确刷新。
     */
    override fun startVideoOutput() {
        if (!surfaceState.hasMount()) return
        // 即使没有待播放的文件，也让 mpv 往这块 Surface 上画（字幕、OSD 等）
        postVideoOutput {
            // 排队期间挂载可能已被放弃或换掉：没有挂载就不开输出。
            if (!surfaceState.hasMount()) return@postVideoOutput
            // 过关后才把输出状态置为开启，失败分支不改状态。
            videoOutputStopped = false
            awaitingSurfaceFrame = true
            val startedAtNanos = System.nanoTime()
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", MpvOptions.VIDEO_OUTPUT)
            val finishedAtNanos = System.nanoTime()
            logVideoOutputTiming("视频输出重建完成", finishedAtNanos - startedAtNanos)
            awaitAudioResume(finishedAtNanos)
        }
    }

    /**
     * 重新武装「下一个新帧上屏」回报：回前台撤掉旧画面后，靠下一条位置前进确认真正的新帧。
     * 不 seek、不改播放位置，也不碰音频。
     */
    override fun awaitFramePresentation() {
        if (released || !surfaceState.hasMount() || videoOutputStopped) return
        awaitingSurfaceFrame = true
    }

    /**
     * 位置中性的精确刷新：seek 0 relative+exact 让播放核心重解当前帧，不改变播放位置。
     * 先等视频输出重开完成，再把命令投到与真实 seek 同一条命令线程，按同一顺序预留并下发。
     * 只有这次刷新自己的序号能触发帧上屏回执；别的序号照常走普通 seek 路径。
     */
    override fun refreshFrame() {
        if (released || !surfaceState.hasMount()) return
        postVideoOutput {
            if (released || !surfaceState.hasMount() || videoOutputStopped) return@postVideoOutput
            val queued = runCatching { seekCommandExecutor.execute { issueRefreshSeek() } }
            if (queued.isFailure) {
                diagnostics.log {
                    "刷新: 命令线程不可用 | ${queued.exceptionOrNull()?.javaClass?.simpleName ?: "-"}"
                }
            }
        }
    }

    /** 命令线程上预留并下发刷新：与真实 seek 共用同一顺序，序号不会互换。 */
    private fun issueRefreshSeek() {
        if (released || !surfaceState.hasMount() || videoOutputStopped) return
        if (!loadCoordinator.isFileLoaded || !hasVideoTrack) return
        if (MPVLib.getPropertyBoolean(EOF_REACHED_PROPERTY) == true) return
        // 刷新会让 audio-pts 跳变：探针基线在命令下发前重新起算，暂停态重建视频输出不误报出声。
        resetAudioResumeBaseline()
        val serial = loadCoordinator.beginRefreshSeek()
        armRefreshTimeout(serial)
        runCatching { MPVLib.command(arrayOf("seek", "0", "relative+exact")) }
            .onFailure { failure ->
                discardRefreshSeek()
                diagnostics.log {
                    "刷新: 下发失败 | ${failure.javaClass.simpleName}: ${failure.message ?: "-"}"
                }
            }
    }

    /** 刷新序号 3 秒内没有被 SEEK 事件消费就标记为坟标，迟到的 SEEK 仍消费它自己。 */
    private fun armRefreshTimeout(serial: Int) {
        synchronized(seekTimeoutLock) {
            if (released) return
            refreshTimeoutFuture?.cancel(false)
            refreshTimeoutFuture = seekTimeoutExecutor.schedule(
                { postRefreshTimeout(serial) },
                REFRESH_SEEK_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun postRefreshTimeout(serial: Int) {
        synchronized(seekTimeoutLock) {
            if (released) return
            runCatching { seekCommandExecutor.execute { handleRefreshTimeout(serial) } }
        }
    }

    private fun handleRefreshTimeout(serial: Int) {
        if (released) return
        val tombstoned = loadCoordinator.onRefreshTimeout(serial) ?: return
        diagnostics.log { "刷新: 3 秒内没有 SEEK 事件，序号标记为坟标=$tombstoned" }
    }

    /** 只是撤掉丢弃计时；刷新归属仍留给它自己的 PLAYBACK_RESTART。 */
    private fun cancelRefreshTimeout() {
        synchronized(seekTimeoutLock) {
            refreshTimeoutFuture?.cancel(false)
            refreshTimeoutFuture = null
        }
    }

    /**
     * 刷新不再需要（翻转、分离或画面已由播放推进确认）。
     * 命令可能已经下发，预留的序号保留队列位作坟标，迟到的 SEEK 只消费它自己。
     */
    private fun cancelRefreshSeek() {
        cancelRefreshTimeout()
        loadCoordinator.cancelRefreshSeek()
    }

    /**
     * 新加载、停止、释放或命令下发失败：这条刷新确定不会再有事件，把预留的序号直接撤掉。
     */
    private fun discardRefreshSeek() {
        cancelRefreshTimeout()
        loadCoordinator.discardRefreshSeek()
    }

    /** 只翻转视频输出：不 seek、不改播放位置，交给播放核心按当前帧重建输出；刷新无回执时由控制层兜底调用。 */
    override fun flipVideoOutput() {
        if (!surfaceState.hasMount() || videoOutputStopped) return
        cancelRefreshSeek()
        awaitingSurfaceFrame = true
        postVideoOutput {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("vo", MpvOptions.VIDEO_OUTPUT)
        }
    }

    /**
     * 分离 Surface：先关输出再摘挂载，顺序不能变；三步作为一条任务交给视频输出线程。
     * 调用线程只翻状态、作废排队中的开输出（一次原子递增），重建整条输出的耗时都在那条线程上。
     * 分离任务不参与代际作废，销毁播放核心之前一定执行到。
     */
    override fun detachSurface() {
        if (released) return
        // 先清掉意图与已挂：排队中的挂载据此放弃，不会挂到刚销毁的 Surface 上。
        if (!surfaceState.detach()) return
        awaitingSurfaceFrame = false
        cancelRefreshSeek()
        videoOutputQueue.invalidate()
        postDetach()
        videoOutputStopped = true
    }

    /** 投递分离任务：先关输出再撤窗口，最后摘挂载；投递被拒时留痕。 */
    private fun postDetach() {
        val accepted = videoOutputQueue.postAlways {
            val startedAtNanos = System.nanoTime()
            detachVideoOutput(
                setProperty = { name, value -> MPVLib.setPropertyString(name, value) },
                setOption = { name, value -> MPVLib.setOptionString(name, value) },
                detachSurface = { MPVLib.detachSurface() },
            )
            // 只量视频输出线程上的拆卸动作；播放核心等它返回的排队时间不在这条里。
            logVideoOutputTiming("拆视频输出（仅视频输出线程动作）", System.nanoTime() - startedAtNanos)
        }
        if (!accepted) diagnostics.log("分离: 视频输出线程不再受理，分离任务没投递")
    }

    /** 把开输出属性交给专用线程；拆挂之后到点的任务作废，释放后不再受理。 */
    private fun postVideoOutput(task: () -> Unit) {
        if (released) return
        videoOutputQueue.postOutput(task)
    }

    /**
     * 尺寸与挂载投同一条队列：同一调用线程内先挂载、再下尺寸、再开输出。
     * 调用线程不碰原生属性；当场挂载与其它线程同时投递的任务会并发，不在这条顺序承诺内。
     */
    override fun setSurfaceSize(width: Int, height: Int) {
        if (released) return
        videoOutputQueue.postSurfaceSize(width, height) { name, value ->
            if (!released) MPVLib.setPropertyString(name, value)
        }
    }

    /**
     * 在等待上限内释放引擎持有的原生资源；闸门超时就放弃销毁，实例随进程退出回收。
     * 还挂着 Surface 时先投一条分离再排空视频输出线程：闸门跑到的分支，销毁播放核心之前一定分离过；
     * 闸门没跑到的分支放弃销毁，那条分支不承诺分离。
     * 排空与销毁由后台线程收尾，发起释放的线程（主线程）不等待视频输出就绪。
     */
    override fun release() {
        // 重复释放：第一次已经销毁或明确放弃销毁，不再动已释放的实例。
        if (released) return
        MPVLib.removeObserver(this)
        MPVLib.removeLogObserver(errorLog)
        // 先挡住新的排程与投递再关线程：释放后到点的计时器不能再排任务。
        synchronized(seekTimeoutLock) {
            released = true
            seekTimeoutFuture?.cancel(false)
            seekTimeoutFuture = null
        }
        seekQueue.reset()
        loadCoordinator.discardRefreshSeek()
        endAudioResume(null)
        audioResumeExecutor.shutdownNow()
        seekTimeoutExecutor.shutdownNow()
        seekCommandExecutor.shutdownNow()
        surfaceTimeoutExecutor.shutdownNow()
        // 视频输出线程可能正卡在 vo 重建里：投一个闸门任务，确认它前面的任务都已跑完。
        // 闸门在 [VIDEO_OUTPUT_RELEASE_WAIT_MILLIS] 内没跑到，说明重建还没结束：
        // 此时放弃销毁，晚到的属性调用不会踩到已销毁的实例，实例随进程退出回收。
        // 作废排队中的开输出：唯一的等待就是下面有上限的闸门。
        // 还挂着 Surface 就先投分离：分离不参与作废，闸门排在它后面，闸门跑到就说明分离过。
        videoOutputQueue.invalidate()
        if (surfaceState.detach()) {
            awaitingSurfaceFrame = false
            postDetach()
        }
        val drained = CountDownLatch(1)
        val queued = videoOutputQueue.postAlways { drained.countDown() }
        // 先登记再启动线程：创建方等的是这个完成信号；线程还没起来也不会立即放行。
        val teardown = nativeCore.registerTeardown()
        val thread = Thread({
            try {
                finishRelease(drained, queued)
            } finally {
                nativeCore.finishTeardown(teardown)
            }
        }, "mpv-release").apply { isDaemon = true }
        try {
            thread.start()
        } catch (failure: Throwable) {
            diagnostics.log { "释放: 收尾线程没起来 | ${failure.javaClass.simpleName}" }
            nativeCore.finishTeardown(teardown)
        }
    }

    /**
     * 等视频输出线程排空后销毁播放核心；排空超时则放弃销毁，实例随进程退出回收。
     */
    private fun finishRelease(
        drained: CountDownLatch,
        queued: Boolean,
    ) {
        val finished = queued &&
            runCatching {
                drained.await(VIDEO_OUTPUT_RELEASE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        videoOutputExecutor.shutdownNow()
        surfaceState.detach()
        videoOutputStopped = true
        if (!finished) {
            diagnostics.log("释放: 视频输出线程未收尾，跳过销毁播放核心")
            return
        }
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
                // 这一跳会把 audio-pts 整体挪走：探针基线重新起算，跳变不算重新出声。
                resetAudioResumeBaseline()
                val event = loadCoordinator.onSeekEvent()
                when {
                    // 坟标只消费迟到的 SEEK，不改写 accepted，也不写 seek 诊断。
                    event?.tombstone == true -> Unit
                    event?.refresh == true -> {
                        // 刷新自己的 SEEK 到了：序号不会错位，撤掉丢弃计时，等它自己的 restart。
                        cancelRefreshTimeout()
                    }
                    else -> seekDiagnostics.onSeekEvent()
                }
                listener?.onLoadActivity(LoadActivity.Seek, loadCoordinator.currentLoadedToken())
            }
            // 播放中优先等 PLAYBACK_RESTART；暂停中只有 VIDEO_RECONFIG，
            // 它可能早于首帧上屏，控制层要等位置真正前进后才算就绪。
            MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                listener?.onLoadActivity(LoadActivity.VideoReconfig, loadCoordinator.currentLoadedToken())
                // 刷新的重配不是新的就绪信号：不占 ready 去重序号，等它的 PLAYBACK_RESTART 补报帧。
                if (
                    !loadCoordinator.isRefreshSeekCurrent() &&
                    loadCoordinator.isFileLoaded &&
                    hasVideoTrack &&
                    isPaused()
                ) {
                    reportReady(needsFrameConfirmation = true)
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG -> {
                logAudioEvent("音频重配")
                listener?.onLoadActivity(LoadActivity.AudioReconfig, loadCoordinator.currentLoadedToken())
                if (loadCoordinator.isFileLoaded && !hasVideoTrack) reportReady()
            }
            // 暂停态的重播同样不能证明画面可显示；纯音频没有帧可等。
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                // 只有刷新自己的序号、且仍在暂停态时才补报回执；其它序号与 resume 照常走普通 ready 与诊断。
                if (isPaused() && loadCoordinator.claimRefreshReceipt() != null) {
                    reportRefreshFrame()
                    return
                }
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

    /**
     * 位置中性刷新的 PLAYBACK_RESTART：按 ADR-0026 的撤黑判据补报一次帧上屏。
     * 只认 Surface 仍挂着、没有开始新加载、有视频轨且没有播到片尾的那一次。
     */
    private fun reportRefreshFrame() {
        if (released || !surfaceState.hasMount() || videoOutputStopped) return
        if (!loadCoordinator.isFileLoaded || !hasVideoTrack) return
        if (MPVLib.getPropertyBoolean(EOF_REACHED_PROPERTY) == true) return
        val token = loadCoordinator.currentLoadedToken() ?: return
        listener?.onFramePresented(token)
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
        discardRefreshSeek()
        val end = loadCoordinator.onEndFile()
        listener?.onLoadActivity(LoadActivity.EndFile, end.spec?.token)
        errorLog.onEndFile(end)
    }

    /**
     * 播放位置前进且 Surface 已挂时，才算新画面真正输出。
     * 暂停态没有位置前进，不会上报，屏上的旧画面得以保留。
     */
    private fun notifyProgressFrame() {
        if (isPaused()) return
        // 纯音频没有画面可证明，首帧信号由 AUDIO_RECONFIG 处理。
        if (!hasVideoTrack) return
        if (!surfaceState.hasMount() || videoOutputStopped) return
        if (!awaitingLoadFrame && !awaitingSurfaceFrame) return
        val token = loadCoordinator.currentLoadedToken() ?: return
        awaitingLoadFrame = false
        awaitingSurfaceFrame = false
        // 画面已经由播放推进确认，刷新的回执不再需要。
        cancelRefreshSeek()
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

    /** 视频输出相关耗时：只在诊断开启时写盘。 */
    private fun logVideoOutputTiming(label: String, elapsedNanos: Long) {
        if (!diagnostics.enabled) return
        diagnostics.log { "$label: 耗时 ${elapsedNanos / 1_000_000}ms" }
    }

    /**
     * 重建完成后等音频重新出声：重建返回时播放核心才不再被阻塞。
     * 判据交给 [AudioResumeWatch]（连续前进才算出声），采样由自己的轮询线程驱动，
     * 上限按墙上时间收摊，不依赖播放位置事件。
     */
    private fun awaitAudioResume(rebuildFinishedAtNanos: Long) {
        if (!diagnostics.enabled || released) return
        val watch = AudioResumeWatch()
        synchronized(audioResumeLock) {
            audioResumeWatch = watch
            audioResumeArmedAtNanos = rebuildFinishedAtNanos
            audioResumeFuture?.cancel(false)
            audioResumeFuture = runCatching {
                audioResumeExecutor.scheduleWithFixedDelay(
                    { probeAudioResume() },
                    AUDIO_RESUME_POLL_INTERVAL_MILLIS,
                    AUDIO_RESUME_POLL_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
            }.getOrNull()
        }
    }

    /** 轮询一次 audio-pts；确认连续前进、没有音频或超过上限就收摊。 */
    private fun probeAudioResume() {
        // 写侧持 [audioResumeLock]：读侧也必须在同一把锁内取快照，不能裸读字段。
        var candidate: AudioResumeWatch? = null
        var armedAtNanos = 0L
        synchronized(audioResumeLock) {
            candidate = audioResumeWatch
            armedAtNanos = audioResumeArmedAtNanos
        }
        val watch = candidate ?: return
        if (released) {
            endAudioResume(null)
            return
        }
        val elapsedMillis = (System.nanoTime() - armedAtNanos) / 1_000_000
        val audioPtsSeconds = readMpvProperty { MPVLib.getPropertyDouble(AUDIO_PTS_PROPERTY) }?.finiteOrNull()
        when (watch.sample(audioPtsSeconds, elapsedMillis)) {
            // 出声时刻取第一次前进那一刻，不是确认那一刻：固定少算一个采样间隔。
            AudioResumeWatch.Sample.Confirmed ->
                endAudioResume("重新出声: 距重建完成 ${watch.resumedAtMillis ?: elapsedMillis}ms")
            AudioResumeWatch.Sample.Unavailable ->
                endAudioResume("重新出声: 没有音频输出")
            AudioResumeWatch.Sample.Idle, AudioResumeWatch.Sample.Pending ->
                if (elapsedMillis > AUDIO_RESUME_WAIT_MILLIS) {
                    endAudioResume("重新出声: ${AUDIO_RESUME_WAIT_MILLIS / 1_000} 秒内没有等到音频连续前进")
                }
        }
    }

    /** 收摊：停掉轮询并按需写一行诊断。 */
    private fun endAudioResume(line: String?) {
        synchronized(audioResumeLock) {
            audioResumeWatch = null
            audioResumeFuture?.cancel(false)
            audioResumeFuture = null
        }
        if (line != null) diagnostics.log { line }
    }

    /** seek 与位置中性刷新会让 audio-pts 跳变：探针基线重新起算，跳变不算出声。 */
    private fun resetAudioResumeBaseline() {
        val watch = synchronized(audioResumeLock) { audioResumeWatch }
        watch?.reset()
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            WIDTH_PROPERTY -> videoWidth = value.toInt()
            HEIGHT_PROPERTY -> videoHeight = value.toInt()
            else -> return
        }
        // 尺寸属于产生它的那次加载：在途加载优先，其次才是已打开的文件。
        // 没有加载归属的尺寸不上报，旧目标迟到的尺寸不能带偏方向。
        val token = loadCoordinator.pendingLoadToken() ?: loadCoordinator.currentLoadedToken() ?: return
        listener?.onVideoSizeChanged(videoWidth, videoHeight, token)
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

    /** 音频输出链变化常驻观测；跟随诊断开关。 */
    override fun eventProperty(property: String, value: String) {
        when (property) {
            AO_PROPERTY, CURRENT_AO_PROPERTY -> logAudioEvent("音频输出 $property=$value")
        }
    }

    /** 音频相关诊断行：只在诊断开启时读属性写盘，失败不影响播放。 */
    private fun logAudioEvent(prefix: String) {
        if (!diagnostics.enabled) return
        runCatching {
            diagnostics.log { "$prefix: ${audioDebugSnapshot().debugText()}" }
        }
    }

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

        /** 重建完成后最多观察音频重新出声的时间；到点只记一行，不重试。 */
        const val AUDIO_RESUME_WAIT_MILLIS = 3_000L

        /**
         * 探针的轮询间隔：不依赖播放位置事件，暂停态也能按时收摊；也是出声时刻的采样粒度。
         * 必须明显大于 [AudioResumeWatch.MIN_ADVANCE_SECONDS]（20ms），
         * 否则相邻两次采样之间的位置变化达不到判据，连续前进永远确认不了。
         */
        const val AUDIO_RESUME_POLL_INTERVAL_MILLIS = 50L

        /** 释放时等视频输出线程排空队列的上限；超时即放弃销毁，不能把退出挂住。 */
        const val VIDEO_OUTPUT_RELEASE_WAIT_MILLIS = 2_000L

        /** 排队挂载等视频输出线程的上限；超时按失败收尾，迟到的任务不再挂载。 */
        const val SURFACE_ATTACH_TIMEOUT_MILLIS = 2_000L

        /** 原生销毁预算：创建等待在排空之外给 destroy 留的时间。 */
        const val NATIVE_DESTROY_BUDGET_MILLIS = 1_000L

        /**
         * 创建前等待上限，显式等于「排空上限 + 销毁预算」：销毁超过这个预算仍在进行时，
         * 创建方拿不到原生锁，放弃本次创建而不是与销毁并发。
         */
        const val NATIVE_CREATE_WAIT_MILLIS =
            VIDEO_OUTPUT_RELEASE_WAIT_MILLIS + NATIVE_DESTROY_BUDGET_MILLIS

        /** 原生核心的创建/销毁接力：进程内唯一。 */
        val nativeCore = NativeCoreLifecycle()

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

        /** `sid` 取该值表示不显示字幕。 */
        const val SID_OFF = "no"
    }
}

/** 视频输出属性名。 */
private const val VIDEO_OUTPUT_PROPERTY = "vo"
private const val FORCE_WINDOW_PROPERTY = "force-window"

/**
 * 分离 Surface 的下发序列：先关输出、再撤窗口，最后摘挂载，三步顺序不能变。
 * 三者作为一条任务投递到视频输出线程，中间不会插进别的输出任务。
 */
internal fun detachVideoOutput(
    setProperty: (String, String) -> Unit,
    setOption: (String, String) -> Unit,
    detachSurface: () -> Unit,
) {
    setProperty(VIDEO_OUTPUT_PROPERTY, "null")
    setOption(FORCE_WINDOW_PROPERTY, "no")
    detachSurface()
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

/** 刷新序号等 SEEK 事件的上限；超过就丢弃，避免共享队首永久错位。 */
