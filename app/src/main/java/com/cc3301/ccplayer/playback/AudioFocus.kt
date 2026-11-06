package com.cc3301.ccplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** 音频焦点变化：短暂丢失在会出声时记住回来后继续，永久丢失要记下不应出声，暂降只压低音量。 */
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

    private val registration = AudioFocusRegistration()

    /**
     * 申请焦点；返回此刻是否已经拿到。延迟授予返回 false 并等 GAIN 回调，
     * 调用方在拿到之前不能出声。
     */
    fun request(): Boolean {
        // 没有音频服务（极少数设备/测试环境）：按已获得处理，不阻断播放。
        val manager = audioManager ?: return true
        if (registration.isGranted) return true
        if (!registration.isRegistered) {
            val result = manager.requestAudioFocus(request)
            registration.onRequest(
                resultGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                resultAccepted = result != AudioManager.AUDIOFOCUS_REQUEST_FAILED,
            )
        }
        return registration.isGranted
    }

    /** 放弃焦点：永久丢失、停止播放与会话结束时调用。 */
    fun abandon() {
        val manager = audioManager ?: return
        if (!registration.isRegistered) return
        registration.abandon()
        manager.abandonAudioFocusRequest(request)
    }

    private fun dispatch(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                registration.onGain()
                onFocusChange(AudioFocusChange.Gain)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                registration.onLoss()
                onFocusChange(AudioFocusChange.Loss)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 短暂丢失期间没有焦点：再次要出声必须等 GAIN 回调，不能凭旧状态放行。
                registration.onTransientLoss()
                onFocusChange(AudioFocusChange.TransientLoss)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusChange(AudioFocusChange.TransientDuck)
        }
    }
}

/**
 * 焦点登记状态：申请结果与三条回调只需要记这几件事，Android 调用由 [AudioFocusRequester] 负责。
 * 单独抽出，便于不依赖 Android 运行时验证「短暂丢失后不能凭旧状态放行」「GAIN 后恢复」。
 */
internal class AudioFocusRegistration {

    /** 已向系统登记申请（含延迟授予）。 */
    var isRegistered = false
        private set

    /** 此刻确实拿着焦点；延迟授予期间为假。 */
    var isGranted = false
        private set

    /** 记下系统申请结果；[resultAccepted] 为假表示申请失败，下次可重试。 */
    fun onRequest(resultGranted: Boolean, resultAccepted: Boolean) {
        isRegistered = resultAccepted
        isGranted = resultGranted
    }

    fun onGain() {
        isGranted = isRegistered
    }

    /** 永久丢失与短暂丢失都意味着当前没有焦点，但短暂丢失仍等 GAIN。 */
    fun onLoss() {
        isGranted = false
    }

    fun onTransientLoss() {
        isGranted = false
    }

    fun abandon() {
        isRegistered = false
        isGranted = false
    }
}

/**
 * 音频焦点的处理规则：暂降时压低音量；短暂丢失只在未暂停、未结束时暂停并记下「恢复后继续」
 * （加载与切集按即将出声处理），用户本来暂停或已结束的保持原样；
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
                // 只有未暂停、未结束（加载与切集按即将出声）才记恢复意图；
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
