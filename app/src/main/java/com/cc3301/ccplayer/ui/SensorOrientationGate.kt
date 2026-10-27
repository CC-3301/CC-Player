package com.cc3301.ccplayer.ui

/**
 * 传感器方向的去抖闸门。
 *
 * 设备架在支架上或平放时，角度可能长时间未知，随后某一帧突然报出有效角度。传感器驱动的换向要
 * 连续 [stableSamples] 帧同一目标才放行，单帧跳变与中途回摆都不换向。角度变成未知时作废待确认
 * 目标，已施加方向保持不变；用户操作与设置变化直接施加，不经过这里。
 */
class SensorOrientationGate(private val stableSamples: Int = STABLE_SAMPLES) {

    private var pending: ScreenOrientation? = null
    private var samples = 0

    /** 角度未知：作废待确认目标，已施加方向保持不变。 */
    fun onAngleUnknown() {
        pending = null
        samples = 0
    }

    /** 用户操作已立即施加：清空待确认计数。 */
    fun reset() {
        pending = null
        samples = 0
    }

    /**
     * 是否放行本次施加：[immediate] 为用户操作与设置变化，直接放行；否则要求目标方向连续稳定。
     */
    fun allowApply(target: ScreenOrientation, immediate: Boolean): Boolean {
        if (immediate) {
            reset()
            return true
        }
        samples = if (target == pending) samples + 1 else 1
        pending = target
        return samples >= stableSamples
    }

    companion object {
        /** 连续成立的帧数；传感器回调约 60ms 一帧，三帧约一两百毫秒。 */
        const val STABLE_SAMPLES = 3
    }
}
