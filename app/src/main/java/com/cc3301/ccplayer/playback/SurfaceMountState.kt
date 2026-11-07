package com.cc3301.ccplayer.playback

import android.view.Surface

/**
 * 挂载两态：会话要求挂载的意图（attached）与真正挂上的 Surface（mounted）。
 *
 * 所有迁移都在同一把锁里；分离或换 Surface 不会插在「检查意图」与「真正挂载」之间。
 */
internal class SurfaceMountState {

    private val lock = Any()

    private var attached: Surface? = null
    private var mounted: Surface? = null

    /** 会话要求挂载 [surface]；返回是否需要发起新的挂载。 */
    fun require(surface: Surface): Boolean = synchronized(lock) {
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

    /** 排队挂载超时：回滚在途意图；返回是否确实回滚了当前意图。 */
    fun onTimedOut(surface: Surface): Boolean = synchronized(lock) {
        if (attached !== surface) return false
        attached = null
        true
    }

    /** 分离：清掉意图与已挂；返回是否需要投递分离任务。 */
    fun detach(): Boolean = synchronized(lock) {
        val had = attached != null || mounted != null
        attached = null
        mounted = null
        had
    }

    /** 当前在途意图是否仍是 [surface]。 */
    fun isIntended(surface: Surface): Boolean = synchronized(lock) { attached === surface }

    /** 是否有在途或已挂载的 Surface。 */
    fun hasMount(): Boolean = synchronized(lock) { attached != null || mounted != null }

    /** 当前在途意图；开输出调用方据此确定目标。 */
    fun intended(): Surface? = synchronized(lock) { attached }
}
