package com.cc3301.ccplayer.ui

import android.content.Context
import android.media.AudioManager

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
