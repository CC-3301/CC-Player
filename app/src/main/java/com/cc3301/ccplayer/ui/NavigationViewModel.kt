package com.cc3301.ccplayer.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cc3301.ccplayer.network.SourceProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用内的界面层级。 */
enum class AppScreen { Home, Sources, Library, Settings }

/** 导航状态：当前界面、源列表所属协议与设置页的来处。 */
data class NavigationState(
    val screen: AppScreen = AppScreen.Home,
    val sourcesProtocol: SourceProtocol = SourceProtocol.Local,
    val settingsReturn: AppScreen = AppScreen.Home,
)

/** 首页返回的结果：第一次提示，窗口内再按一次退出。 */
enum class HomeBackResult { ShowExitHint, Exit }

/**
 * 导航状态持有者：界面层级与设置页来处放在这里，由 ViewModel 持有，界面重建后不丢。
 * 首页二次返回的窗口按注入的时钟判定，便于测试。
 */
class NavigationViewModel(
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val now: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _state = MutableStateFlow(restoredState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var lastExitHintAt: Long? = null

    fun openProtocol(protocol: SourceProtocol) {
        lastExitHintAt = null
        update { it.copy(screen = AppScreen.Sources, sourcesProtocol = protocol) }
    }

    fun openLibrary() {
        update { it.copy(screen = AppScreen.Library) }
    }

    fun openSettings() {
        lastExitHintAt = null
        update { if (it.screen == AppScreen.Settings) it else it.copy(settingsReturn = it.screen, screen = AppScreen.Settings) }
    }

    /** 设置页返回进入前的界面。 */
    fun closeSettings() {
        update { it.copy(screen = it.settingsReturn) }
    }

    fun backToSources() {
        update { it.copy(screen = AppScreen.Sources) }
    }

    fun backToHome() {
        update { it.copy(screen = AppScreen.Home) }
    }

    /** 首页系统返回：第一次提示，距上次提示不超过 2 秒时退出。 */
    fun onHomeBack(): HomeBackResult {
        val current = now()
        val last = lastExitHintAt
        lastExitHintAt = current
        return if (last != null && current - last in 0..EXIT_WINDOW_MILLIS) {
            lastExitHintAt = null
            HomeBackResult.Exit
        } else {
            HomeBackResult.ShowExitHint
        }
    }

    private fun update(transform: (NavigationState) -> NavigationState) {
        val updated = transform(_state.value)
        _state.value = updated
        savedState[KEY_SCREEN] = updated.screen.name
        savedState[KEY_SOURCES_PROTOCOL] = updated.sourcesProtocol.name
        savedState[KEY_SETTINGS_RETURN] = updated.settingsReturn.name
    }

    private fun restoredState(): NavigationState = NavigationState(
        screen = savedState.enumValue(KEY_SCREEN, AppScreen.entries, AppScreen.Home),
        sourcesProtocol = savedState.enumValue(KEY_SOURCES_PROTOCOL, SourceProtocol.entries, SourceProtocol.Local),
        settingsReturn = savedState.enumValue(KEY_SETTINGS_RETURN, AppScreen.entries, AppScreen.Home),
    )

    private fun <T : Enum<T>> SavedStateHandle.enumValue(key: String, entries: List<T>, default: T): T =
        entries.firstOrNull { it.name == get<String>(key) } ?: default

    companion object {
        const val EXIT_WINDOW_MILLIS = 2_000L

        private const val KEY_SCREEN = "navigation_screen"
        private const val KEY_SOURCES_PROTOCOL = "navigation_sources_protocol"
        private const val KEY_SETTINGS_RETURN = "navigation_settings_return"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { NavigationViewModel(createSavedStateHandle()) }
        }
    }
}
