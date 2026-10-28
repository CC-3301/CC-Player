package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * Surface 会话：视频输出与 Surface 挂载的唯一所有者。
 *
 * Surface 回调、尺寸变化与服务连接只向这里转发。
 * 拆挂只由 SurfaceHolder 回调触发：surfaceCreated 挂载，surfaceDestroyed 只标记不可用；
 * Activity 的停止与恢复不拆不挂。挂载与分离幂等，可重复、可乱序。
 * 后台抓帧完成前不主动分离，由调用方在抓帧结束后调用 [detach]。
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

    /** 当前挂在引擎上的 Surface；与 [surface] 分开记录，后台销毁后仍要等抓帧结束再分离。 */
    private var mountedSurface: Surface? = null

    /** 已挂载的 Surface 已被销毁；即使对象身份被复用，也必须先分离再重挂。 */
    private var mountedSurfaceDestroyed = false
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

    /** Surface 已销毁：只标记不可用，分离由调用方在后台抓帧结束后调用 [detach] 完成。 */
    fun onSurfaceDestroyed() {
        surface = null
        if (mounted) mountedSurfaceDestroyed = true
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
        publish()
    }

    private fun attachCurrent() {
        val target = surface?.takeIf(isValid) ?: return
        // 同一个 Surface 且没有被销毁才是幂等挂载；对象身份不能当作会话 ID。
        if (mountedSurface === target && !mountedSurfaceDestroyed) return
        detachIfMounted()
        if (!attach(target)) return
        mountedSurface = target
        mountedSurfaceDestroyed = false
        // 挂载顺序：先挂 Surface，再设尺寸，最后开窗口并恢复视频输出。
        if (surfaceWidth > 0 && surfaceHeight > 0) resize(surfaceWidth, surfaceHeight)
        startOutput()
    }

    private fun detachIfMounted() {
        if (!mounted) return
        detachEngine()
        mountedSurface = null
        mountedSurfaceDestroyed = false
    }

    private fun publish() {
        val next = when {
            !mounted -> State.Detached
            hasSurface && !mountedSurfaceDestroyed -> State.Ready
            else -> State.Attached
        }
        if (next == state) return
        state = next
        // 只有真正可渲染的 Ready 才算可用；Attached 表示引擎还挂着但当前 Surface 已无效。
        onAvailabilityChanged(next == State.Ready)
    }
}
