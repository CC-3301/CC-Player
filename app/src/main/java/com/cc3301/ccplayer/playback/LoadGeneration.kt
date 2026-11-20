package com.cc3301.ccplayer.playback

/** 没有目标的下标。 */
internal const val NO_MEDIA_INDEX = -1

/** 没有在途加载令牌。 */
internal const val NO_LOAD_TOKEN = 0

/** 一次加载的进入方式；决定画面交接、出声归属与失败回退。 */
internal enum class LoadKind {
    /** 首次打开：没有可沿用的旧目标，屏幕从黑屏开始。 */
    First,

    /** 从浏览界面打开另一个目标：撤旧画面、旧片立停出声，新目标就绪后才放声。 */
    Open,

    /** 切集：上一集、下一集、播放列表选集与片尾自动；旧画面留到目标首帧。 */
    Switch,

    /** 恢复原片：切集失败或取消后回到切集前的目标。 */
    Restore,
}

/** 一次加载所处的阶段。 */
internal enum class LoadPhase {
    /** 打开交接已经开始、新目标还没到：没有引擎加载在跑。 */
    Pending,

    /** 首次打开或打开新目标的加载。 */
    Initial,

    /** 切集加载。 */
    Switching,

    /** 恢复原片加载。 */
    Restoring,

    /** 没有进行中的加载：当前目标已就绪或在播放、暂停。 */
    None,
}

/**
 * 当前代际的出声归属。
 *
 * [Opening] 只属于打开交接：旧片已经停声、新目标还没就绪，这段交接期间谁都不该出声。
 * 归属挂在代际上，代际被替换或作废的那一刻它就不存在了，不需要在每条路径上手工解除静音。
 */
internal enum class AudioOwner {
    /** 出声不由本代际掌管：切集与恢复都以暂停开始，出不出声由引擎状态决定。 */
    None,

    /** 目标可以出声。 */
    Target,

    /** 打开交接中：旧片已停声，新目标尚未就绪。 */
    Opening,
}

/**
 * 打开交接前的代际事实：撤销交接时原样恢复。
 *
 * 加载令牌也要放回：旧目标仍在播放，它之后的播完与出帧必须继续算数。
 */
internal data class HandoverSnapshot(
    val kind: LoadKind,
    val screenFrame: ScreenFrame,
    val originAspectRatio: Double?,
    val holdBlack: Boolean,
    val quietBlack: Boolean,
    val audioOwner: AudioOwner,
    val loadToken: Int = NO_LOAD_TOKEN,
    val mediaLoaded: Boolean = false,
    val hasVideo: Boolean = true,
)

/**
 * 一次加载的代际：目标、引擎令牌、出声归属、屏上画面与就绪证据的唯一持有者。
 *
 * 每次新的加载意图（首次、打开、切集、恢复）建立一个代际；改目标与重发沿用同一代际，
 * 只更新代际内部的目标与这次引擎加载的令牌。所有引擎回调先按 [accepts] 校验是不是当前代际，
 * 旧代际的完成、出帧与失败一律丢弃。代际在目标就绪后继续持有屏上画面与比例，
 * 直到下一次加载意图替换它或会话结束把它作废。
 *
 * 上面这些事实只能由本类的迁移方法修改：调用方只读事实，不逐字段组装。
 */
internal class LoadGeneration(
    nowMillis: () -> Long,
) {
    /** 打开交接期间旧会话自然播完：这次打开返回时按片尾处理。 */
    var handoverEndedAtEpisode: Boolean = false
        private set
    /** 当前进入方式；打开交接会推进本代际的进入方式。 */
    var kind: LoadKind = LoadKind.First
        private set

    /** 当前阶段；[LoadPhase.None] 表示加载已结束、画面归本代际所有。 */
    var phase: LoadPhase = LoadPhase.None
        private set

    /** 当前目标在播放列表里的下标与交给引擎的位置。 */
    var targetIndex: Int = NO_MEDIA_INDEX
        private set
    var targetUrl: String? = null
        private set

    /** 打开交接中为真：旧片已停声、旧画面已撤，等新目标。 */
    val isHandoverPending: Boolean
        get() = kind == LoadKind.Open && phase == LoadPhase.Pending

    /** 当前这次引擎加载的令牌；没有在途加载时为 [NO_LOAD_TOKEN]。 */
    var loadToken: Int = NO_LOAD_TOKEN
        private set

    /** 出声归属；静音只由它（与预览静音）推导。 */
    var audioOwner: AudioOwner = AudioOwner.None
        private set

    /** 屏上停着哪一帧；[ScreenFrame.Origin] 表示切集期间沿用上一代的画面。 */
    var screenFrame: ScreenFrame = ScreenFrame.None
        private set

    /** 屏上那一帧的比例；只有 [ScreenFrame.Origin] 时使用。 */
    var originAspectRatio: Double? = null
        private set

    /** 片尾黑底；直到用户操作或新加载才解除。 */
    var holdBlack: Boolean = false
        private set

    /** 黑屏期间不显示缓冲提示：后台切集与播放态回前台撤帧这两类黑屏。 */
    var quietBlack: Boolean = false
        private set

    /** 打开交接的撤销快照；新目标一开始加载就失效。 */
    var handover: HandoverSnapshot? = null
        private set

    /**
     * 交接开始时旧会话的在途加载令牌：只在旧会话已就绪（有可恢复画面）时记录。
     * 旧会话还在首次加载或切集时，结束事件不属于「旧会话播完」，不在这里吞掉。
     */
    var handoverLoadToken: Int = NO_LOAD_TOKEN
        private set

    /** 目标文件是否已打开、是否有视频轨。 */
    var mediaLoaded: Boolean = false
        private set
    var hasVideo: Boolean = true
        private set

    /** 本轮加载的起点与引擎上报过的时长；就绪时用来初始化进度。 */
    var startPositionMillis: Long = 0L
    var durationMillis: Long = 0L

    /** 就绪判据：续播 seek、续播查询、目标接管视频输出。 */
    var seekPendingForReady: Boolean = false
        private set
    var requiredSeekSerial: Int = 0
        private set
    var resumeLookupDone: Boolean = true
        private set
    var deferredReadyNeedsFrame: Boolean? = null
        private set
    var outputLive: Boolean = false
        private set

    /** 看护与事件时间线：都按本代际自己的累计运行时长记。 */
    val elapsed = RunningDuration(nowMillis)

    var completedAtMillis: Long? = null
    var firstFrameAtMillis: Long? = null
    var positionAdvancedAtMillis: Long? = null
    var outputLiveAtMillis: Long? = null
    var retriedAtMillis: Long? = null
    var guardRetried: Boolean = false
    var retryInFlight: Boolean = false
    var retrySerial: Int = 0
    var byteSourceRebuilds: Int = 0
    var discardSkipped: Boolean = false
    val retargets: MutableList<String> = mutableListOf()

    /** 首次打开：没有可沿用的旧目标，屏上从黑屏开始，目标可以出声。 */
    fun beginFirst(targetIndex: Int, targetUrl: String?) {
        beginInitial(LoadKind.First, targetIndex, targetUrl)
        audioOwner = AudioOwner.Target
        quietBlack = false
    }

    /** 打开新目标：接管打开交接，新目标就绪前谁都不出声，黑屏期间不显示缓冲提示。 */
    fun beginOpen(targetIndex: Int, targetUrl: String?) {
        beginInitial(LoadKind.Open, targetIndex, targetUrl)
        audioOwner = AudioOwner.Opening
        quietBlack = true
    }

    private fun beginInitial(kind: LoadKind, targetIndex: Int, targetUrl: String?) {
        this.kind = kind
        phase = LoadPhase.Initial
        this.targetIndex = targetIndex
        this.targetUrl = targetUrl
        // 新目标一开始加载，交接快照与交接期间的片尾事实都不再成立。
        handover = null
        handoverEndedAtEpisode = false
        outputLive = false
        screenFrame = ScreenFrame.None
        originAspectRatio = null
        holdBlack = false
    }

    /**
     * 切集：目标画面就绪前沿用切集起点的原片画面与比例。
     * [inheritOrigin] 为假（后台切集、打开交接之后的切集）时铺黑等新帧；
     * [quietBlack] 为真表示这次黑屏不显示缓冲提示。
     */
    fun beginSwitch(
        targetIndex: Int,
        targetUrl: String?,
        inheritOrigin: Boolean,
        originAspectRatio: Double?,
        quietBlack: Boolean,
    ) {
        kind = LoadKind.Switch
        phase = LoadPhase.Switching
        this.targetIndex = targetIndex
        this.targetUrl = targetUrl
        audioOwner = AudioOwner.None
        screenFrame = if (inheritOrigin) ScreenFrame.Origin else ScreenFrame.None
        this.originAspectRatio = originAspectRatio
        holdBlack = false
        this.quietBlack = quietBlack
        outputLive = false
    }

    /** 改目标：切集缓冲期间把目标前移，沿用同一代际与阶段。 */
    fun retarget(targetIndex: Int, targetUrl: String?) {
        this.targetIndex = targetIndex
        this.targetUrl = targetUrl
        phase = LoadPhase.Switching
        outputLive = false
    }

    /**
     * 恢复原片：沿用出错前的屏上画面与比例，[holdBlack] 为真时按黑底等原片重新出帧。
     * 恢复一律以暂停开始，出声由引擎状态决定。
     */
    fun beginRestore(
        targetIndex: Int,
        targetUrl: String?,
        screenFrame: ScreenFrame,
        originAspectRatio: Double?,
        holdBlack: Boolean,
    ) {
        kind = LoadKind.Restore
        phase = LoadPhase.Restoring
        this.targetIndex = targetIndex
        this.targetUrl = targetUrl
        audioOwner = AudioOwner.None
        this.screenFrame = screenFrame
        this.originAspectRatio = originAspectRatio
        this.holdBlack = holdBlack
        quietBlack = false
        outputLive = false
    }

    /**
     * 以本代际的目标开始一次引擎加载：改目标与重发都只换这里的令牌。
     * 新的一次尝试还没有加载完成，上一轮的加载与就绪证据随之作废。
     */
    fun beginAttempt(loadToken: Int) {
        this.loadToken = loadToken
        clearLoadEvidence()
    }

    /** 本代际当前的加载作废（打开交接接管、会话结束）。 */
    fun forgetLoad() {
        loadToken = NO_LOAD_TOKEN
    }

    /** 回调是否属于本代际：令牌与目标地址都对上才算。 */
    fun accepts(loadToken: Int, targetUrl: String?): Boolean =
        loadToken != NO_LOAD_TOKEN &&
            loadToken == this.loadToken &&
            (targetUrl == null || targetUrl == this.targetUrl)

    /** 媒体加载完成：记下文件已打开与目标有没有视频轨。 */
    fun noteMediaLoaded(hasVideo: Boolean) {
        mediaLoaded = true
        this.hasVideo = hasVideo
    }

    /**
     * 进入打开交接：旧片停声、旧画面撤掉，屏上铺黑。
     * 旧目标已就绪时留下快照供撤销；旧目标还在加载时没有可恢复的画面事实。
     */
    fun startHandover(snapshot: HandoverSnapshot?) {
        kind = LoadKind.Open
        handoverLoadToken = if (snapshot != null) loadToken else NO_LOAD_TOKEN
        handoverEndedAtEpisode = false
        phase = LoadPhase.Pending
        forgetLoad()
        audioOwner = AudioOwner.Opening
        screenFrame = ScreenFrame.None
        originAspectRatio = null
        holdBlack = false
        quietBlack = true
        handover = snapshot
        mediaLoaded = false
        hasVideo = true
        outputLive = false
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
    }

    /** 撤销打开交接：把快照里的画面事实、出声归属与加载令牌原样放回本代际。 */
    fun restoreHandover(snapshot: HandoverSnapshot) {
        // 交接撤销后旧会话回到当前会话：交接期间的片尾事实不再成立。
        handoverEndedAtEpisode = false
        handoverLoadToken = NO_LOAD_TOKEN
        kind = snapshot.kind
        phase = LoadPhase.None
        audioOwner = snapshot.audioOwner
        screenFrame = snapshot.screenFrame
        originAspectRatio = snapshot.originAspectRatio
        holdBlack = snapshot.holdBlack
        quietBlack = snapshot.quietBlack
        loadToken = snapshot.loadToken
        mediaLoaded = snapshot.mediaLoaded
        hasVideo = snapshot.hasVideo
        handover = null
    }

    /** 目标就绪：加载阶段结束，画面归本代际；就绪证据与交接快照一起清掉。 */
    fun markReady() {
        phase = LoadPhase.None
        handover = null
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
    }

    /** 目标接管视频输出：记录这次加载已经开始出画面。 */
    fun noteOutputLive() {
        outputLive = true
    }

    /** 进入新一轮加载：上一轮的加载与就绪证据作废；[resumeLookupDone] 表示这次不用再查续播位置。 */
    fun resetReadyForLoad(resumeLookupDone: Boolean) {
        clearLoadEvidence()
        outputLive = false
        // 上一轮加载自己的画面不再算数；切集起点的原片画面仍然沿用。
        if (screenFrame == ScreenFrame.Target) screenFrame = ScreenFrame.None
        requiredSeekSerial = 0
        this.resumeLookupDone = resumeLookupDone
    }

    /** 媒体还没加载完成、就绪证据作废：新的一次尝试与新的一轮加载共用这一组重置。 */
    private fun clearLoadEvidence() {
        mediaLoaded = false
        hasVideo = true
        seekPendingForReady = false
        deferredReadyNeedsFrame = null
    }

    /** 续播位置已查明。 */
    fun noteResumeLookupDone() {
        resumeLookupDone = true
    }

    /** 续播 seek 已下发：就绪要等序号不小于它的新画面信号。 */
    fun noteResumeSeek(requiredSeekSerial: Int) {
        seekPendingForReady = true
        this.requiredSeekSerial = requiredSeekSerial
    }

    /** 续播 seek 之后的首帧已到：这一跳不再拦就绪。 */
    fun settleResumeSeek() {
        seekPendingForReady = false
    }

    /**
     * 续播位置未查明时收到的首帧信号先记下：
     * 查到位置就作废（等 seek 后的新信号），没查到位置则补用它。
     */
    fun deferReady(needsFrameConfirmation: Boolean) {
        deferredReadyNeedsFrame = needsFrameConfirmation
    }

    /** 取走暂存的首帧信号并清空；没有暂存时返回 null。 */
    fun takeDeferredReady(): Boolean? {
        val deferred = deferredReadyNeedsFrame
        deferredReadyNeedsFrame = null
        return deferred
    }

    /** 目标帧已上屏：屏上换成当前加载的画面，黑屏提示随之解除。 */
    fun showTargetFrame() {
        screenFrame = ScreenFrame.Target
        quietBlack = false
    }

    /** 撤掉屏上的帧与比例；这次黑屏是否显示提示保持不变。 */
    fun dropFrame() {
        screenFrame = ScreenFrame.None
        originAspectRatio = null
    }

    /** 撤掉屏上的帧与比例，并让这次黑屏不显示缓冲提示。 */
    fun dropFrameQuietly() {
        dropFrame()
        quietBlack = true
    }

    /** 接管信号到了却等不到帧：撤掉屏上的帧，按黑底等原片重新出帧。 */
    fun abandonFrame() {
        screenFrame = ScreenFrame.None
        holdBlack = true
    }

    /** 片尾黑底：用户操作或新加载时解除。 */
    fun setHoldBlack(hold: Boolean) {
        holdBlack = hold
    }

    /** 黑屏期间是否显示缓冲提示。 */
    fun setQuietBlack(quiet: Boolean) {
        quietBlack = quiet
    }

    /** 打开交接的目标出画面或加载完成：解除本代际的交接静音，返回是否真的解除了。 */
    fun releaseAudioOwnership(): Boolean {
        if (audioOwner != AudioOwner.Opening) return false
        audioOwner = AudioOwner.Target
        return true
    }

    /** 打开交接的新目标开始加载：撤销快照到此失效。 */
    fun clearHandover() {
        handover = null
    }

    /** 交接窗口内旧会话自然播完：只记片尾事实，不触发连播。 */
    fun noteHandoverEndedAtEpisode() {
        handoverEndedAtEpisode = true
    }
}

/** 当前是否处在打开交接的静音里；预览静音是另一份来源，两者都只决定这一条命令。 */
internal fun mutedByOpening(generation: LoadGeneration?, previewMuted: Boolean): Boolean =
    previewMuted || generation?.audioOwner == AudioOwner.Opening
