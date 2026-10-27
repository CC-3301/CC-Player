package com.cc3301.ccplayer.ui

/**
 * 传感器方向的去抖闸门。
 *
 * 设备架在支架上或平放时，角度可能长时间未知，随后某一帧突然报出有效角度。传感器驱动的换向要
 * 连续 [stableSamples] 帧同一目标才放行，单帧跳变与中途回摆都不换向。角度变成未知或用户已经
 * 亲自施加方向时，待确认目标作废，已施加方向保持不变。
 */
class SensorOrientationGate(private val stableSamples: Int = STABLE_SAMPLES) {

    private var pending: ScreenOrientation? = null
    private var samples = 0

    /** 角度未知：作废待确认目标，已施加方向保持不变。 */
    fun onAngleUnknown() = clearPending()

    /** 用户操作已立即施加：清空待确认计数。 */
    fun onUserApplied() = clearPending()

    /**
     * 这一帧传感器是否达到施加条件：目标方向连续 [stableSamples] 帧一致才返回 true。
     * 只更新计数，不改变任何方向。
     */
    fun shouldApplyFromSensor(target: ScreenOrientation): Boolean {
        samples = if (target == pending) samples + 1 else 1
        pending = target
        return samples >= stableSamples
    }

    private fun clearPending() {
        pending = null
        samples = 0
    }

    companion object {
        /** 连续成立的帧数；传感器回调约 60ms 一帧，三帧约一两百毫秒。 */
        const val STABLE_SAMPLES = 3
    }
}
