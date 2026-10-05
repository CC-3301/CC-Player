package com.cc3301.ccplayer.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager

/**
 * 亮度写进当前窗口的 [android.view.Window#getAttributes] 覆盖值：只影响本应用画面，不改系统亮度设置。
 */
class WindowBrightnessControl(private val activity: Activity) : BrightnessControl {

    /** 窗口没有亮度覆盖值时按中档起调。 */
    override fun read(): Float {
        val current = activity.window.attributes.screenBrightness
        return if (current < 0f) DEFAULT_FRACTION else current
    }

    override fun write(fraction: Float) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = fraction.coerceIn(0f, 1f)
        activity.window.attributes = attributes
    }

    private companion object {
        const val DEFAULT_FRACTION = 0.5f
    }
}

/** 音量写进设备的媒体音量档位。 */
class MediaVolumeControl(context: Context) : VolumeControl {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val maxVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    override fun read(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun write(volume: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume.coerceIn(0, maxVolume), 0)
    }
}
