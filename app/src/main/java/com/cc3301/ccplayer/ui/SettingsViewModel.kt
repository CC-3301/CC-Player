package com.cc3301.ccplayer.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cc3301.ccplayer.playback.DecodeMode
import com.cc3301.ccplayer.settings.OrientationMode
import com.cc3301.ccplayer.settings.ResumeMode
import com.cc3301.ccplayer.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 设置页展示的全部持久设置项。 */
data class SettingsState(
    val autoPlayNext: Boolean = SettingsRepository.DEFAULT_AUTO_PLAY_NEXT,
    val resumeMode: ResumeMode = SettingsRepository.DEFAULT_RESUME_MODE,
    val autoRestorePosition: Boolean = SettingsRepository.DEFAULT_AUTO_RESTORE_POSITION,
    val decodeMode: DecodeMode = SettingsRepository.DEFAULT_DECODE_MODE,
    val orientationMode: OrientationMode = SettingsRepository.DEFAULT_ORIENTATION_MODE,
)

/**
 * 设置页的状态持有者：把持久设置汇集给界面，并把界面上的改动写回设置。
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val playback = combine(
                settings.autoPlayNext,
                settings.resumeMode,
                settings.autoRestorePosition,
                settings.decodeMode,
                settings.orientationMode,
            ) { autoPlayNext, resumeMode, autoRestore, decodeMode, orientationMode ->
                PlaybackSettings(autoPlayNext, resumeMode, autoRestore, decodeMode, orientationMode)
            }
            playback.collect { playbackSettings ->
                _state.value = SettingsState(
                    autoPlayNext = playbackSettings.autoPlayNext,
                    resumeMode = playbackSettings.resumeMode,
                    autoRestorePosition = playbackSettings.autoRestorePosition,
                    decodeMode = playbackSettings.decodeMode,
                    orientationMode = playbackSettings.orientationMode,
                )
            }
        }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoPlayNext(enabled) }
    }

    fun setResumeMode(mode: ResumeMode) {
        viewModelScope.launch { settings.setResumeMode(mode) }
    }

    fun setAutoRestorePosition(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoRestorePosition(enabled) }
    }

    fun setDecodeMode(mode: DecodeMode) {
        viewModelScope.launch { settings.setDecodeMode(mode) }
    }

    fun setOrientationMode(mode: OrientationMode) {
        viewModelScope.launch { settings.setOrientationMode(mode) }
    }

    /** 播放相关的设置项。 */
    private data class PlaybackSettings(
        val autoPlayNext: Boolean,
        val resumeMode: ResumeMode,
        val autoRestorePosition: Boolean,
        val decodeMode: DecodeMode,
        val orientationMode: OrientationMode,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(SettingsRepository.create(context.applicationContext)) }
        }
    }
}
