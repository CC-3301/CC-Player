package com.cc3301.ccplayer.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView

/**
 * 播放画面载体：把 TextureView 的 Surface 生命周期转给播放引擎，本身不做渲染。
 *
 * Surface 由应用进程持有，窗口不可见时不销毁，因此退到后台不更换窗口句柄、不重建视频输出。
 * 只有视图真正离开窗口、SurfaceTexture 被释放时才上报释放。
 */
class MpvTextureView(context: Context, attrs: AttributeSet? = null) :
    TextureView(context, attrs), TextureView.SurfaceTextureListener {

    var callback: Callback? = null

    /** 当前 SurfaceTexture 上的 Surface：同一块纹理只包一次，释放回调里归还。 */
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        callback?.onSurfaceReady(surfaceOf(surfaceTexture))
        // 可用回调自带视图尺寸：先挂载，再按同一尺寸下发。
        if (width > 0 && height > 0) callback?.onSurfaceResized(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        callback?.onSurfaceResized(width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        callback?.onSurfaceReleased()
        // 分离是异步投递到视频输出线程的，这里不等它跑完：等会阻塞界面线程数百毫秒。
        // libmpv 在 attachSurface 时自己持有窗口引用，归还这层包装不会提前释放底层窗口，
        // 真正的窗口归还发生在 detachSurface 执行时；返回 true 表示 SurfaceTexture 由 TextureView 释放。
        releaseSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    private fun surfaceOf(surfaceTexture: SurfaceTexture): Surface {
        val existing = surface
        if (existing != null && texture === surfaceTexture) return existing
        releaseSurface()
        texture = surfaceTexture
        return Surface(surfaceTexture).also { surface = it }
    }

    private fun releaseSurface() {
        surface?.release()
        surface = null
        texture = null
    }

    interface Callback {
        fun onSurfaceReady(surface: Surface)

        fun onSurfaceResized(width: Int, height: Int)

        fun onSurfaceReleased()
    }
}
