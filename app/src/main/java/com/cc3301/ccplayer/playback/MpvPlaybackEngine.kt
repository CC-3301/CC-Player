package com.cc3301.ccplayer.playback

import android.content.Context
import android.view.Surface
import `is`.xyz.mpv.MPVLib

/**
 * 用 mpv-android 官方 libmpv 构建产物实现的播放引擎。
 *
 * 渲染入口是 [MPVLib.attachSurface]：libmpv 的 render API 直接把画面画到视图的 Surface 上，
 * 相当于 mpv-android 示例工程里 MPVView 承担的渲染职责。
 */
class MpvPlaybackEngine(
    private val context: Context,
    private val cacheDir: String,
) : PlaybackEngine, MPVLib.EventObserver {

    private var listener: PlaybackEngine.Listener? = null

    /** detachSurface 会把视频输出关掉，重新接上 Surface 前要恢复它。 */
    private var videoOutputStopped = false

    fun initialize() {
        MPVLib.create(context.applicationContext)
        MpvOptions.initial(cacheDir).forEach { (option, value) -> MPVLib.setOptionString(option, value) }
        MPVLib.init()
        MPVLib.addObserver(this)
        MPVLib.observeProperty(PAUSE_PROPERTY, MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    override fun setListener(listener: PlaybackEngine.Listener) {
        this.listener = listener
    }

    override fun start(url: String) {
        MPVLib.command(arrayOf("loadfile", url, "replace"))
    }

    override fun pause() {
        MPVLib.setPropertyBoolean(PAUSE_PROPERTY, true)
    }

    override fun resume() {
        MPVLib.setPropertyBoolean(PAUSE_PROPERTY, false)
    }

    override fun stop() {
        MPVLib.command(arrayOf("stop"))
    }

    override fun attachSurface(surface: Surface) {
        if (videoOutputStopped) {
            MPVLib.setPropertyString("vo", MpvOptions.VIDEO_OUTPUT)
            videoOutputStopped = false
        }
        MPVLib.attachSurface(surface)
        // 即使没有待播放的文件，也让 mpv 往这块 Surface 上画（字幕、OSD 等）
        MPVLib.setOptionString("force-window", "yes")
    }

    override fun detachSurface() {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
        videoOutputStopped = true
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun release() {
        MPVLib.removeObserver(this)
        MPVLib.destroy()
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> listener?.onMediaLoaded()
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> listener?.onPlaybackEnded()
        }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) = Unit

    override fun eventProperty(property: String, value: Double) = Unit

    override fun eventProperty(property: String, value: String) = Unit

    override fun eventProperty(property: String, value: Boolean) {
        if (property == PAUSE_PROPERTY) listener?.onPauseChanged(value)
    }

    private companion object {
        const val PAUSE_PROPERTY = "pause"
    }
}
