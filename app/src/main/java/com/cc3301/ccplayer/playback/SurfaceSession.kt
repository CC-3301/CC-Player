package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * Surface 会话：视频输出与 Surface 挂载的唯一所有者。
 *
 * Activity 生命周期、Surface 回调、尺寸变化与服务连接只向这里转发。
 * 挂载与分离幂等；回前台优先复用已保存的 Surface，不依赖 surfaceCreated 再次触发。
 * 后台抓帧完成前不主动分离，由调用方在抓帧结束后调用 [detach]。
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
    private var foreground = false

    /** 当前挂在引擎上的 Surface；与 [surface] 分开记录，后台销毁后仍要等抓帧结束再分离。 */
    private var mountedSurface: Surface? = null

    /** 已挂载的 Surface 已被销毁；即使对象身份被复用，也必须先分离再重挂。 */
    private var mountedSurfaceDestroyed = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val mounted: Boolean get() = mountedSurface != null

    /** 是否有可挂载的 Surface；回前台时用它恢复画面。 */
    val hasSurface: Boolean get() = surface?.let(isValid) == true

    fun onActivityStarted() {
        foreground = true
        attachCurrent()
        publish()
    }

    fun onActivityStopped() {
        foreground = false
        publish()
    }

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

    fun onSurfaceDestroyed() {
        surface = null
        if (mounted) mountedSurfaceDestroyed = true
        // 退后台时先让抓帧跑完，分离由调用方在抓帧结束后触发。
        if (foreground) detachIfMounted()
        publish()
    }

    /** 当前是否可渲染；服务重连时由调用方同步一次。 */
    val available: Boolean get() = state == State.Ready

    /** 播放服务重新连上时调用；已在前台则把保存的 Surface 挂回去。 */
    fun onEngineAvailable() {
        attachCurrent()
        publish()
    }

    /** 播放服务断开：旧引擎的挂载不再有效，重新连上时补挂。 */
    fun onEngineLost() {
        mountedSurface = null
        mountedSurfaceDestroyed = false
        publish()
    }

    /** 后台抓帧结束后分离；重复调用无副作用。 */
    fun detach() {
        detachIfMounted()
        publish()
    }

    fun release() {
        detachIfMounted()
        surface = null
        foreground = false
        publish()
    }

    private fun attachCurrent() {
        if (!foreground) return
        val target = surface?.takeIf(isValid) ?: return
        // 同一个 Surface 且没有被销毁才是幂等挂载；对象身份不能当作会话 ID。
        if (mountedSurface === target && !mountedSurfaceDestroyed) return
        detachIfMounted()
        if (!attach(target)) return
        mountedSurface = target
        mountedSurfaceDestroyed = false
        if (surfaceWidth > 0 && surfaceHeight > 0) resize(surfaceWidth, surfaceHeight)
    }

    private fun detachIfMounted() {
        if (!mounted) return
        detachEngine()
        mountedSurface = null
        mountedSurfaceDestroyed = false
    }

    private fun publish() {
        val next = when {
            !foreground || !mounted -> State.Detached
            hasSurface && !mountedSurfaceDestroyed -> State.Ready
            else -> State.Attached
        }
        if (next == state) return
        state = next
        // 只有真正可渲染的 Ready 才算可用；Attached 表示引擎还挂着但当前 Surface 已无效。
        onAvailabilityChanged(next == State.Ready)
    }
}
