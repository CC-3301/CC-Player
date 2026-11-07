package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * Surface 会话：视频输出与 Surface 挂载的唯一所有者。
 *
 * Surface 回调、尺寸变化与服务连接只向这里转发。
 * 拆挂只由 Surface 生命周期回调触发：Surface 就绪时挂载，Surface 真正销毁时先标记不可用、
 * 再分离；Activity 的停止与恢复（窗口不可见）不拆不挂。挂载与分离幂等，可重复、可乱序。
 */
class SurfaceSession(
    private val attach: (Surface) -> Boolean,
    private val startOutput: () -> Unit,
    private val detachEngine: () -> Unit,
    private val resize: (Int, Int) -> Unit,
    private val onAvailabilityChanged: (Boolean) -> Unit,
    private val isValid: (Surface) -> Boolean = { it.isValid },
) {

    enum class State { Detached, Attached, Ready }

    var state: State = State.Detached
        private set

    private var surface: Surface? = null

    /** 当前挂在引擎上的 Surface；与 [surface] 分开记录，销毁时先标记不可用。 */
    private var mountedSurface: Surface? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val mounted: Boolean get() = mountedSurface != null

    /** 当前是否有可挂载且有效的 Surface。 */
    val hasSurface: Boolean get() = surface?.let(isValid) == true

    fun onSurfaceCreated(surface: Surface) {
        this.surface = surface
        attachCurrent()
        publish()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (mounted) resize(width, height)
    }

    /**
     * Surface 真正销毁：先标记不可用，再分离挂载。窗口不可见不会走到这里，
     * 画面载体由应用进程持有，系统不会因为窗口不可见而释放它。
     */
    fun onSurfaceDestroyed() {
        surface = null
        detachIfMounted()
        publish()
    }

    /** 当前是否可渲染；服务重连时由调用方同步一次。 */
    val available: Boolean get() = state == State.Ready

    /** 播放服务重新连上时调用；有有效 Surface 则补挂。 */
    fun onEngineAvailable() {
        attachCurrent()
        publish()
    }

    /** 播放服务断开：旧引擎的挂载不再有效，重新连上时补挂。 */
    fun onEngineLost() {
        mountedSurface = null
        publish()
    }

    fun release() {
        detachIfMounted()
        surface = null
        publish()
    }

    private fun attachCurrent() {
        val target = surface?.takeIf(isValid) ?: return
        // 同一个 Surface 才是幂等挂载；对象身份不能当作会话 ID，销毁后的重挂要先分离。
        if (mountedSurface === target) return
        detachIfMounted()
        if (!attach(target)) return
        mountedSurface = target
        // 挂载顺序：先挂 Surface，再设尺寸，最后开窗口并恢复视频输出。
        if (surfaceWidth > 0 && surfaceHeight > 0) resize(surfaceWidth, surfaceHeight)
        startOutput()
    }

    private fun detachIfMounted() {
        if (!mounted) return
        detachEngine()
        mountedSurface = null
    }

    private fun publish() {
        val next = when {
            !mounted -> State.Detached
            hasSurface -> State.Ready
            else -> State.Attached
        }
        if (next == state) return
        state = next
        // 只有真正可渲染的 Ready 才算可用；Attached 表示引擎还挂着但当前 Surface 已无效。
        onAvailabilityChanged(next == State.Ready)
    }
}
