package com.cc3301.ccplayer.ui

/** 松手后的淡出时长，毫秒。 */
const val PLAYER_PRESS_FADE_MILLIS = 120L

/** 按下后才判定不可执行时的撤除时长，毫秒。 */
const val PLAYER_PRESS_REVOKE_MILLIS = 60L

/** 按压反馈的阶段：未按下、按住、松手淡出、被拦下撤除。 */
enum class PlayerPressPhase { Idle, Held, Fading, Revoked }

/**
 * 按压反馈的计时：按住期间保持白色 20% 方块，松手后按 [PLAYER_PRESS_FADE_MILLIS] 线性淡出，
 * 按下后才判定不可执行时按 [PLAYER_PRESS_REVOKE_MILLIS] 撤除。
 * 时间由 [now] 提供，测试注入假时钟。
 */
class PlayerPressFeedback(private val now: () -> Long = System::currentTimeMillis) {
    var phase = PlayerPressPhase.Idle
        private set

    private var phaseStart = 0L
    private var fadeFrom = 0f

    /** 按下：进入按住阶段。 */
    fun press() {
        phase = PlayerPressPhase.Held
        phaseStart = now()
    }

    /** 松手：从按住进入淡出；未按住时不改变状态。 */
    fun release() {
        if (phase != PlayerPressPhase.Held) return
        fadeFrom = alpha()
        phase = PlayerPressPhase.Fading
        phaseStart = now()
    }

    /** 按下后才判定不可执行：从当前不透明度起按 [PLAYER_PRESS_REVOKE_MILLIS] 撤除。 */
    fun revoke() {
        if (phase == PlayerPressPhase.Idle || phase == PlayerPressPhase.Revoked) return
        fadeFrom = alpha()
        phase = PlayerPressPhase.Revoked
        phaseStart = now()
    }

    /**
     * 按帧推进不透明度，直到归零；按住与空闲只取一次值，不请求帧。
     * 帧时钟由 [onFrame] 注入。
     */
    suspend fun animate(onFrame: suspend () -> Unit, onAlpha: (Float) -> Unit) {
        while (true) {
            val value = alpha()
            onAlpha(value)
            if (value <= 0f) return
            if (phase != PlayerPressPhase.Fading && phase != PlayerPressPhase.Revoked) return
            onFrame()
        }
    }

    /** 当前应绘制的不透明度。 */
    fun alpha(): Float = when (phase) {
        PlayerPressPhase.Idle -> 0f
        PlayerPressPhase.Held -> PLAYER_PRESS_RIPPLE_ALPHA
        PlayerPressPhase.Fading -> fade(fadeFrom, now() - phaseStart, PLAYER_PRESS_FADE_MILLIS)
        PlayerPressPhase.Revoked -> fade(fadeFrom, now() - phaseStart, PLAYER_PRESS_REVOKE_MILLIS)
    }
}

/** 从 [from] 线性降到 0；超过 [duration] 后为零。 */
private fun fade(from: Float, elapsed: Long, duration: Long): Float {
    if (elapsed >= duration) return 0f
    if (elapsed <= 0) return from
    return from * (1f - elapsed.toFloat() / duration)
}
