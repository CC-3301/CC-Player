package com.cc3301.ccplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** 音频焦点变化：短暂丢失要记住回来后继续，暂降只压低音量。 */
enum class AudioFocusChange { Gain, Loss, TransientLoss, TransientDuck }

/**
 * 音频焦点申请：播放开始时申请，暂停不释放，退后台不做任何焦点操作。
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

    private var held = false

    /** 申请焦点；已经持有或系统没有音频服务时不重复申请。 */
    fun request() {
        val manager = audioManager ?: return
        if (held) return
        // 延迟授予同样先算持有：拿到焦点后由回调继续，失败才留待下次播放重试。
        held = manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    /** 放弃焦点：永久丢失与停止播放时调用。 */
    fun abandon() {
        val manager = audioManager ?: return
        if (!held) return
        held = false
        manager.abandonAudioFocusRequest(request)
    }

    private fun dispatch(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onFocusChange(AudioFocusChange.Gain)
            AudioManager.AUDIOFOCUS_LOSS -> onFocusChange(AudioFocusChange.Loss)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusChange(AudioFocusChange.TransientLoss)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusChange(AudioFocusChange.TransientDuck)
        }
    }
}

/**
 * 音频焦点的处理规则：暂降时压低音量，短暂丢失暂停并在焦点回来后继续，永久丢失保持暂停。
 * 只作用于播放控制层与引擎音量，不碰 Surface、视频输出与加载。
 */
class AudioFocusPolicy(
    private val pausePlayback: () -> Unit,
    private val resumePlayback: () -> Unit,
    private val setDucked: (Boolean) -> Unit,
    private val isPlaying: () -> Boolean,
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
                if (!isPlaying()) return
                resumeAfterGain = true
                pausePlayback()
            }
            AudioFocusChange.Loss -> {
                duck(false)
                resumeAfterGain = false
                if (isPlaying()) pausePlayback()
            }
        }
    }

    private fun duck(on: Boolean) {
        if (ducked == on) return
        ducked = on
        setDucked(on)
    }
}
