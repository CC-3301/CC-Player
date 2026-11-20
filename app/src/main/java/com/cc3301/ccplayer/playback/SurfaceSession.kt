package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * Surface 会话：把 Surface 回调与播放服务连接转成挂载恢复要的生命周期事件。
 *
 * 只做 Android 与宿主生命周期 adapter：Surface 就绪时报告挂载，Surface 真正销毁时报告分离；
 * Activity 的停止与恢复（窗口不可见）不拆不挂。挂载、尺寸与输出顺序归挂载恢复，
 * 这里只跟踪当前 Surface 与可渲染状态。挂载与分离幂等，可重复、可乱序。
 */
class SurfaceSession(
    private val attach: (Surface) -> Boolean,
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

    /** 最近一次视图尺寸：引擎晚于尺寸事件连上时，挂载前补报一次。 */
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

    /** 尺寸变化只转发：先挂载、再尺寸、再开输出由挂载恢复保证。 */
    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        resize(width, height)
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
        // 同一个 Surface 才是幂等挂载；对象身份不能当作会话 ID，换 Surface 时先分离由挂载恢复负责。
        if (mountedSurface === target) return
        // 尺寸先于引擎到达时补报一次：挂载时补下尺寸、再开输出的顺序仍归挂载恢复。
        if (surfaceWidth > 0 && surfaceHeight > 0) resize(surfaceWidth, surfaceHeight)
        if (!attach(target)) return
        mountedSurface = target
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
