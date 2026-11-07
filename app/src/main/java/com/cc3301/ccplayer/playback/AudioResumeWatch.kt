package com.cc3301.ccplayer.playback

/**
 * 视频输出重建后「重新出声」的判据。
 *
 * `audio-pts` 是已输出音频帧的位置：正常播放时连续前进，seek 与位置中性刷新会让它跳变。
 * 单次跳变不足以证明一直在出声，必须连续两次采样都前进才算确认；任一次没有前进就打断连续，
 * 下一次前进重新起算。出声时刻取这段连续前进里第一次前进的时刻，不是确认那一刻。
 * 判据只覆盖重建解除阻塞之后的恢复时间，不覆盖阻塞期间已经发生的断音本身。
 */
internal class AudioResumeWatch(
    private val minAdvanceSeconds: Double = MIN_ADVANCE_SECONDS,
    private val requiredAdvances: Int = REQUIRED_ADVANCES,
    private val unavailableAfterMisses: Int = UNAVAILABLE_MISSES,
) {

    /** 一次采样的判定结果。 */
    enum class Sample {
        /** 本次没有前进（停顿、原地不动或读不到）：连续中断，累计清零。 */
        Idle,

        /** 前进了一次，还没达到确认次数。 */
        Pending,

        /** 连续前进达到确认次数：音频重新出声。 */
        Confirmed,

        /** 连续多次读不到 `audio-pts`：没有音频输出，不再等。 */
        Unavailable,
    }

    /** 连续多次读不到才算没有音频输出：重建窗口里的短暂读不到不能据此收摊。 */
    var unavailable: Boolean = false
        private set

    /** 这段连续前进里第一次前进的时刻；确认出声时用它作为出声时刻。 */
    var resumedAtMillis: Long? = null
        private set

    private var previousSeconds: Double? = null
    private var advances = 0
    private var misses = 0

    /** 送入一次采样：只由探针的轮询线程调用；seek 与刷新线程只调 [reset]。 */
    @Synchronized
    fun sample(audioPtsSeconds: Double?, elapsedMillis: Long): Sample {
        if (unavailable) return Sample.Unavailable
        if (audioPtsSeconds == null) {
            // 重建期间可能短暂读不到：单次按「没有前进」处理并清掉基线，
            // 避免下一次读到旧值被当成跳变；连续多次才算没有音频输出。
            misses++
            previousSeconds = null
            advances = 0
            resumedAtMillis = null
            if (misses >= unavailableAfterMisses) {
                unavailable = true
                return Sample.Unavailable
            }
            return Sample.Idle
        }
        misses = 0
        val previous = previousSeconds
        previousSeconds = audioPtsSeconds
        val advanced = previous != null && audioPtsSeconds > previous + minAdvanceSeconds
        if (!advanced) {
            // 停顿或原地不动打断连续：下一个前进重新起算。
            advances = 0
            resumedAtMillis = null
            return Sample.Idle
        }
        advances++
        if (advances == 1) resumedAtMillis = elapsedMillis
        return if (advances >= requiredAdvances) Sample.Confirmed else Sample.Pending
    }

    /** seek 与位置中性刷新会让 audio-pts 跳变：基线、连续计数与首次前进时刻一起清掉。 */
    @Synchronized
    fun reset() {
        previousSeconds = null
        advances = 0
        resumedAtMillis = null
    }

    companion object {
        /** 单次采样视为前进的最小位移，秒。 */
        const val MIN_ADVANCE_SECONDS = 0.02

        /** 连续前进次数：一次跳变不足以证明音频一直在出声。 */
        const val REQUIRED_ADVANCES = 2

        /** 连续多少次采样读不到 audio-pts 才算没有音频输出（50ms 轮询下约 300ms）。 */
        const val UNAVAILABLE_MISSES = 6
    }
}
