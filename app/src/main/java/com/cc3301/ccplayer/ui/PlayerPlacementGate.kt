package com.cc3301.ccplayer.ui

/** 首次撤黑的阶段。 */
enum class PlayerPlacementPhase {
    /** 比例或首帧还没到，还没开始等方向。 */
    Idle,

    /** 正在等屏幕转过来：继续盖黑。 */
    Waiting,

    /** 已有结论（方向对齐或等满时限）：本次播放不再等方向、不再重新盖黑。 */
    Settled,
}

/**
 * 播放页首次撤黑门。
 *
 * 进页时盖着黑等屏幕转过来：横向视频落在竖向屏幕上时继续铺黑，
 * 等方向对齐或等满 [PLAYER_ORIENTATION_WAIT_LIMIT_MILLIS] 再撤黑。
 * 一轮只有“对齐”与“到时限”两个结论；结案后本次播放不再等方向、不再重新盖黑，
 * 方向回摆与首帧抖动都不会重开计时。
 *
 * 比例未知期间一直盖黑由比例判据负责，不进这里，也不设超时。
 */
data class PlayerPlacementGate(val phase: PlayerPlacementPhase = PlayerPlacementPhase.Idle) {
    /** 本次播放的首次撤黑是否已有结论。 */
    val settled: Boolean get() = phase == PlayerPlacementPhase.Settled

    /**
     * 是否还在等方向：为真时继续盖黑。
     *
     * 比例与首帧都到位、且这次确实要转屏时才开始（见 [awaitsVideoRotation]）；
     * 等待开始后即使首帧短暂消失也按“还在等”处理，计时不重来。
     */
    fun awaitsOrientation(
        ratioKnown: Boolean,
        showsPicture: Boolean,
        rotationPending: Boolean,
    ): Boolean = when (phase) {
        PlayerPlacementPhase.Settled -> false
        // 等待期间只看方向：首帧短暂消失也不把计时键翻回去，2 秒不会重新开始。
        PlayerPlacementPhase.Waiting -> rotationPending
        PlayerPlacementPhase.Idle -> ratioKnown && showsPicture && rotationPending
    }

    /** 每帧喂一次事实：开始时进入等待，方向对齐即结案。 */
    fun withFrame(
        ratioKnown: Boolean,
        showsPicture: Boolean,
        rotationPending: Boolean,
    ): PlayerPlacementGate = when (phase) {
        PlayerPlacementPhase.Settled -> this
        // 比例丢失时“不再要转屏”并不成立，不据此结案：等回来的比例再判，或由时限收尾。
        PlayerPlacementPhase.Waiting ->
            if (rotationPending || !ratioKnown) this else PlayerPlacementGate(PlayerPlacementPhase.Settled)
        PlayerPlacementPhase.Idle -> when {
            ratioKnown && showsPicture && rotationPending -> PlayerPlacementGate(PlayerPlacementPhase.Waiting)
            ratioKnown && showsPicture -> PlayerPlacementGate(PlayerPlacementPhase.Settled)
            else -> this
        }
    }

    /** 等待到时限：无条件结案，不管方向是否对齐。 */
    fun withWaitLimit(): PlayerPlacementGate =
        if (phase == PlayerPlacementPhase.Waiting) {
            PlayerPlacementGate(PlayerPlacementPhase.Settled)
        } else {
            this
        }
}
