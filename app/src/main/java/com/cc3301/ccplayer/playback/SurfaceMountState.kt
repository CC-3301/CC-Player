package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * 挂载三态：会话想要的 Surface（desired）、正在挂载的意图（attached）、真正挂上的（mounted）。
 *
 * 所有迁移都在同一把锁里，超时重挂的「检查 + 真正挂载」也在锁内完成；因此分离或换 Surface
 * 不可能插在检查与挂载之间：要么先清掉意图让重挂放弃，要么等重挂挂完再分离。
 */
internal class SurfaceMountState {

    /** 重挂结果：挂上、意图已变或被分离、已有挂载在途。 */
    enum class RemountResult { Attached, Superseded, Pending }

    private val lock = Any()

    private var desired: Surface? = null
    private var attached: Surface? = null
    private var mounted: Surface? = null

    /** 会话要求挂载 [surface]；返回是否需要发起新的挂载。 */
    fun require(surface: Surface): Boolean = synchronized(lock) {
        desired = surface
        if (attached === surface) return false
        attached = surface
        true
    }

    /** 挂载任务真正执行完：只有在途意图仍是它时才算挂上。 */
    fun onMounted(surface: Surface) {
        synchronized(lock) {
            if (attached === surface) mounted = surface
        }
    }

    /** 排队挂载被跳过：回滚在途意图；返回是否确实回滚了当前意图。 */
    fun onSkipped(surface: Surface): Boolean = synchronized(lock) {
        if (attached !== surface) return false
        attached = null
        true
    }

    /** 排队挂载超时：回滚在途意图；返回会话是否仍要这块 Surface（决定是否重挂）。 */
    fun onTimedOut(surface: Surface): Boolean = synchronized(lock) {
        if (attached !== surface) return false
        attached = null
        desired === surface
    }

    /**
     * 队列恢复后重挂：检查与 [attach] 同临界区，期间分离或换 Surface 不会插进来。
     * [attach] 在锁内执行，调用方应在其中完成真正的挂载。
     */
    fun remount(surface: Surface, attach: () -> Unit): RemountResult = synchronized(lock) {
        if (desired !== surface) return RemountResult.Superseded
        if (attached != null) return RemountResult.Pending
        attached = surface
        attach()
        mounted = surface
        RemountResult.Attached
    }

    /** 分离：清掉三态；返回是否需要投递分离任务。 */
    fun detach(): Boolean = synchronized(lock) {
        desired = null
        val had = attached != null || mounted != null
        attached = null
        mounted = null
        had
    }

    /** 当前在途意图是否仍是 [surface]。 */
    fun isIntended(surface: Surface): Boolean = synchronized(lock) { attached === surface }

    /** 真正挂着的 Surface 是不是 [surface]；开输出这类判据只认它。 */
    fun isMounted(surface: Surface): Boolean = synchronized(lock) { mounted === surface }

    /** 是否有在途或已挂载的 Surface。 */
    fun hasMount(): Boolean = synchronized(lock) { attached != null || mounted != null }

    /** 当前在途意图；开输出调用方据此确定目标。 */
    fun intended(): Surface? = synchronized(lock) { attached }
}
