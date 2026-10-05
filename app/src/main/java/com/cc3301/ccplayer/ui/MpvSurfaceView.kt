package com.cc3301.ccplayer.ui

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.util.AttributeSet

/**
 * 播放画面承载视图：把 Surface 的生命周期转给播放引擎，本身不做渲染。
 */
class MpvSurfaceView(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

    var callback: Callback? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        callback?.onSurfaceReady(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        callback?.onSurfaceResized(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        callback?.onSurfaceReleased()
    }

    interface Callback {
        fun onSurfaceReady(surface: Surface)

        fun onSurfaceResized(width: Int, height: Int)

        fun onSurfaceReleased()
    }
}
