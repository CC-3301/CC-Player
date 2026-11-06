package com.cc3301.ccplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** 音频焦点变化：短暂丢失要记住回来后继续，永久丢失要记下不应出声，暂降只压低音量。 */
enum class AudioFocusChange { Gain, Loss, TransientLoss, TransientDuck }

/**
 * 音频焦点申请：播放开始申请，暂停不释放，退后台不做任何焦点操作。
 * 申请与放弃都在主线程，回调也回到主线程。
 */
class AudioFocusRequester(
    context: Context,
    private val onFocusChange: (AudioFocusChange) -> Unit,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener { change -> dispatch(change) }
        .build()

    /** 已向系统登记申请（含延迟授予）。 */
    private var registered = false

    /** 此刻确实拿着焦点；延迟授予期间为假。 */
    private var granted = false

    /**
     * 申请焦点；返回此刻是否已经拿到。延迟授予返回 false 并等 GAIN 回调，
     * 调用方在拿到之前不能出声。
     */
    fun request(): Boolean {
        // 没有音频服务（极少数设备/测试环境）：按已获得处理，不阻断播放。
        val manager = audioManager ?: return true
        if (granted) return true
        if (!registered) {
            val result = manager.requestAudioFocus(request)
            registered = result != AudioManager.AUDIOFOCUS_REQUEST_FAILED
            granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return granted
    }

    /** 放弃焦点：永久丢失、停止播放与会话结束时调用。 */
    fun abandon() {
        val manager = audioManager ?: return
        if (!registered) return
        registered = false
        granted = false
        manager.abandonAudioFocusRequest(request)
    }

    private fun dispatch(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                granted = registered
                onFocusChange(AudioFocusChange.Gain)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                granted = false
                onFocusChange(AudioFocusChange.Loss)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 短暂丢失期间没有焦点：再次要出声必须等 GAIN 回调，不能凭旧状态放行。
                granted = false
                onFocusChange(AudioFocusChange.TransientLoss)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusChange(AudioFocusChange.TransientDuck)
        }
    }
}

/**
 * 音频焦点的处理规则：暂降时压低音量；短暂丢失不管当前是否在出声都记下「恢复后继续」；
 * 永久丢失记下「不应出声」，恢复播放前必须重新拿到焦点；放弃焦点或会话结束时回到基准音量。
 * 只作用于播放控制层与引擎音量，不碰 Surface、视频输出与加载。
 */
class AudioFocusPolicy(
    private val pausePlayback: () -> Unit,
    private val resumePlayback: () -> Unit,
    private val setDucked: (Boolean) -> Unit,
    private val isPaused: () -> Boolean,
) {

    private var ducked = false
    private var resumeAfterGain = false

    fun onFocusChange(change: AudioFocusChange) {
        when (change) {
            AudioFocusChange.TransientDuck -> duck(true)
            AudioFocusChange.Gain -> {
                duck(false)
                if (!resumeAfterGain) return
                resumeAfterGain = false
                resumePlayback()
            }
            AudioFocusChange.TransientLoss -> {
                duck(false)
                // 只有「正在出声、因这次丢失才暂停」才记恢复意图；
                // 用户本来暂停的保持暂停，GAIN 回来不替用户继续。
                if (isPaused()) return
                resumeAfterGain = true
                pausePlayback()
            }
            AudioFocusChange.Loss -> {
                duck(false)
                resumeAfterGain = false
                pauseIfAudible()
            }
        }
    }

    /** 要出声但没拿到焦点：先停住；焦点回来时按恢复意图继续。 */
    fun waitForFocus() {
        resumeAfterGain = true
        pauseIfAudible()
    }

    /** 会话结束或放弃焦点：回基准音量与初始意图。 */
    fun reset() {
        duck(false)
        resumeAfterGain = false
    }

    /** 已经在暂停/结束态就不重复下发暂停：会误清片尾黑底之类的状态。 */
    private fun pauseIfAudible() {
        if (isPaused()) return
        pausePlayback()
    }

    private fun duck(on: Boolean) {
        if (ducked == on) return
        ducked = on
        setDucked(on)
    }
}
