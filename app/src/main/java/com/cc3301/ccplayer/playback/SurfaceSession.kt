package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * Surface 会话：把 Surface 回调与播放服务连接转成挂载恢复要的生命周期事件。
 *
 * 只做 Android 与宿主生命周期 adapter：Surface 就绪时报告挂载，Surface 真正销毁时报告分离；
 * Activity 的停止与恢复（窗口不可见）不拆不挂。挂载、尺寸、输出顺序与可渲染事实归挂载恢复，
 * 这里只保留当前 Surface 与它的有效性。挂载与分离幂等，可重复、可乱序。
 */
class SurfaceSession(
    private val attach: (Surface) -> Unit,
    private val detach: () -> Unit,
    private val resize: (Int, Int) -> Unit,
    private val isValid: (Surface) -> Boolean = { it.isValid },
) {

    private var surface: Surface? = null

    /** 当前是否有可挂载且有效的 Surface。 */
    val hasSurface: Boolean get() = surface?.let(isValid) == true

    fun onSurfaceCreated(surface: Surface) {
        this.surface = surface
        attachCurrent()
    }

    /** 尺寸变化只转发：挂载、尺寸与开输出的顺序归挂载恢复。 */
    fun onSurfaceChanged(width: Int, height: Int) {
        resize(width, height)
    }

    /**
     * Surface 真正销毁：报告分离。窗口不可见不会走到这里，
     * 画面载体由应用进程持有，系统不会因为窗口不可见而释放它。
     */
    fun onSurfaceDestroyed() {
        surface = null
        detach()
    }

    /** 播放服务重新连上时调用；有有效 Surface 则补挂。 */
    fun onEngineAvailable() {
        attachCurrent()
    }

    fun release() {
        surface = null
        detach()
    }

    private fun attachCurrent() {
        val target = surface?.takeIf(isValid) ?: return
        attach(target)
    }
}
