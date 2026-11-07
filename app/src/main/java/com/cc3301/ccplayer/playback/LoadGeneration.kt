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
 * 一次加载的代际：目标、引擎令牌、出声归属与就绪证据的唯一持有者。
 *
 * 每次新的加载意图（首次、打开、切集、恢复）建立一个代际；改目标与重发沿用同一代际，
 * 只更新代际内部的目标与这次引擎加载的令牌。所有引擎回调先按 [accepts] 校验是不是当前代际，
 * 旧代际的完成、出帧与失败一律丢弃。代际在目标就绪后继续持有屏上画面与比例，
 * 直到下一次加载意图替换它或会话结束把它作废。
 */
internal class LoadGeneration(
    /** 入口分配的代际号；同一会话的每个加载代际都沿用它。 */
    val id: Int,
    nowMillis: () -> Long,
) {
    /** 当前进入方式；打开交接会推进本代际的进入方式。 */
    var kind: LoadKind = LoadKind.First

    /** 当前阶段；[LoadPhase.None] 表示加载已结束、画面归本代际所有。 */
    var phase: LoadPhase = LoadPhase.None

    /** 当前目标在播放列表里的下标与交给引擎的位置。 */
    var targetIndex: Int = NO_MEDIA_INDEX
    var targetUrl: String? = null

    /** 打开交接中为真：旧片已停声、旧画面已撤，等新目标。 */
    val isHandoverPending: Boolean
        get() = kind == LoadKind.Open && phase == LoadPhase.Pending

    /** 当前这次引擎加载的令牌；没有在途加载时为 [NO_LOAD_TOKEN]。 */
    var loadToken: Int = NO_LOAD_TOKEN
        private set

    /** 出声归属；静音只由它（与预览静音）推导。 */
    var audioOwner: AudioOwner = AudioOwner.None

    /** 屏上停着哪一帧；[ScreenFrame.Origin] 表示切集期间沿用上一代的画面。 */
    var screenFrame: ScreenFrame = ScreenFrame.None

    /** 屏上那一帧的比例；只有 [ScreenFrame.Origin] 时使用。 */
    var originAspectRatio: Double? = null

    /** 片尾黑底；直到用户操作或新加载才解除。 */
    var holdBlack: Boolean = false

    /** 黑屏期间不显示缓冲提示：后台切集、播放态回前台撤帧与打开交接这三类黑屏。 */
    var quietBlack: Boolean = false

    /** 打开交接的撤销快照；新目标一开始加载就失效。 */
    var handover: HandoverSnapshot? = null
        private set

    /** 目标文件是否已打开、是否有视频轨。 */
    var mediaLoaded: Boolean = false
    var hasVideo: Boolean = true

    /** 本轮加载的起点与引擎上报过的时长；就绪时用来初始化进度。 */
    var startPositionMillis: Long = 0L
    var durationMillis: Long = 0L

    /** 就绪判据：续播 seek、续播查询、目标接管视频输出。 */
    var seekPendingForReady: Boolean = false
    var requiredSeekSerial: Int = 0
    var resumeLookupDone: Boolean = true
    var deferredReadyNeedsFrame: Boolean? = null
    var outputLive: Boolean = false

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

    /** 以本代际的目标开始一次引擎加载：改目标与重发都只换这里的令牌。 */
    fun beginAttempt(loadToken: Int) {
        this.loadToken = loadToken
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

    /**
     * 进入打开交接：旧片停声、旧画面撤掉，屏上铺黑。
     * 旧目标已就绪时留下快照供撤销；旧目标还在加载时没有可恢复的画面事实。
     */
    fun startHandover(snapshot: HandoverSnapshot?) {
        kind = LoadKind.Open
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

    /** 目标就绪：加载阶段结束，画面归本代际。 */
    fun markReady() {
        phase = LoadPhase.None
        handover = null
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
}

/** 当前是否处在打开交接的静音里；预览静音是另一份来源，两者都只决定这一条命令。 */
internal fun mutedByOpening(generation: LoadGeneration?, previewMuted: Boolean): Boolean =
    previewMuted || generation?.audioOwner == AudioOwner.Opening
