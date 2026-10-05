package com.cc3301.ccplayer.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * 用系统广播实现的音频输出中断监听：ACTION_AUDIO_BECOMING_NOISY 在耳机拔出或蓝牙断连时发出。
 */
class AndroidAudioOutputMonitor(private val context: Context) : AudioOutputMonitor {

    private var receiver: BroadcastReceiver? = null

    override fun start(onOutputLost: () -> Unit) {
        if (receiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onOutputLost()
            }
        }
        this.receiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun stop() {
        val receiver = this.receiver ?: return
        context.unregisterReceiver(receiver)
        this.receiver = null
    }
}
