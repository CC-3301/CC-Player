package com.cc3301.ccplayer.playback

import com.cc3301.ccplayer.settings.SettingsRepository

/**
 * 后台播放策略：播放服务持有唯一的前后台与开关状态，决定暂停时机、通知动作与动作分发。
 * 界面只上报前后台，不自行判断暂停。
 */
class BackgroundPlaybackPolicy(
    private val pausePlayback: () -> Unit,
    private val onPolicyChanged: () -> Unit = {},
) {

    var backgroundPlaybackEnabled: Boolean = SettingsRepository.DEFAULT_BACKGROUND_PLAYBACK
        private set

    var appInForeground: Boolean = true
        private set

    /** 后台且关闭后台播放：此时通知栏只保留暂停。 */
    val blocksBackgroundPlayback: Boolean
        get() = !backgroundPlaybackEnabled && !appInForeground

    fun setBackgroundPlaybackEnabled(enabled: Boolean) {
        if (enabled == backgroundPlaybackEnabled) return
        backgroundPlaybackEnabled = enabled
        pauseIfBlocked()
        onPolicyChanged()
    }

    fun setAppInForeground(foreground: Boolean) {
        val changed = foreground != appInForeground
        appInForeground = foreground
        // 重复上报也要检查一次：播放可能在同步之后才真正开始。
        pauseIfBlocked()
        if (changed) onPolicyChanged()
    }

    /** 后台关闭时只允许暂停；其余情况不限制。 */
    fun allows(action: MediaSessionAction): Boolean =
        if (blocksBackgroundPlayback) action == MediaSessionAction.Pause else true

    fun dispatch(action: MediaSessionAction, apply: (MediaSessionAction) -> Unit) {
        if (allows(action)) apply(action)
    }

    /**
     * 通知栏动作集合：后台关闭时只剩暂停，同时按当前加载阶段收窄，
     * 避免显示点了没反应的按钮。
     */
    fun notificationActions(
        state: PlaybackState,
        switching: Boolean = false,
        initialLoading: Boolean = false,
        restoring: Boolean = false,
    ): List<MediaSessionAction> = notificationActions(
        state = state,
        switching = switching,
        initialLoading = initialLoading,
        restoring = restoring,
        blocksBackgroundPlayback = blocksBackgroundPlayback,
    )

    private fun pauseIfBlocked() {
        if (blocksBackgroundPlayback) pausePlayback()
    }
}
