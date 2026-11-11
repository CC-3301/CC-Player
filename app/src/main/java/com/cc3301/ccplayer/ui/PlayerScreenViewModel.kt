package com.cc3301.ccplayer.ui

import androidx.lifecycle.ViewModel
import com.cc3301.ccplayer.playback.PlaybackState

/**
 * 播放页的状态持有者：收下播放控制层的事实，派生出播放页阶段。
 */
class PlayerScreenViewModel : ViewModel() {

    private var facts = PlayerScreenLoadFacts(
        entryPhase = null,
        preparesInPage = false,
        playbackState = PlaybackState.Idle,
        switching = false,
        restoring = false,
    )

    /** 收下当前事实；阶段在读取时派生。 */
    fun update(facts: PlayerScreenLoadFacts) {
        this.facts = facts
    }

    /** 当前播放页阶段。 */
    val phase: PlayerScreenPhase
        get() = playerScreenPhase(facts)
}
