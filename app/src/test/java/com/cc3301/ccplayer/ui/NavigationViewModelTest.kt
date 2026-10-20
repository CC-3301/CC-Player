package com.cc3301.ccplayer.ui

import androidx.lifecycle.SavedStateHandle
import com.cc3301.ccplayer.network.SourceProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/** 导航状态：逐级回退、设置页来处、首页二次返回窗口与重建后的恢复。 */
class NavigationViewModelTest {

    private var now = 0L
    private val savedState = SavedStateHandle()
    private val navigation = NavigationViewModel(savedState) { now }

    @Test
    fun `默认停在首页`() {
        assertEquals(NavigationState(), navigation.state.value)
    }

    @Test
    fun `子目录到源列表到首页逐级回退`() {
        navigation.openProtocol(SourceProtocol.Smb)
        assertEquals(AppScreen.Sources, navigation.state.value.screen)
        assertEquals(SourceProtocol.Smb, navigation.state.value.sourcesProtocol)

        navigation.openLibrary()
        assertEquals(AppScreen.Library, navigation.state.value.screen)

        navigation.backToSources()
        assertEquals(AppScreen.Sources, navigation.state.value.screen)

        navigation.backToHome()
        assertEquals(AppScreen.Home, navigation.state.value.screen)
    }

    @Test
    fun `库页面有上级目录时回退上级且不离开库页面`() {
        navigation.openProtocol(SourceProtocol.Smb)
        navigation.openLibrary()

        assertEquals(LibraryBackAction.NavigateUp, navigation.backFromLibrary(hasParent = true))
        assertEquals(AppScreen.Library, navigation.state.value.screen)
    }

    @Test
    fun `库页面在根目录时返回源列表`() {
        navigation.openProtocol(SourceProtocol.Smb)
        navigation.openLibrary()

        assertEquals(LibraryBackAction.LeaveLibrary, navigation.backFromLibrary(hasParent = false))
        assertEquals(AppScreen.Sources, navigation.state.value.screen)
    }

    @Test
    fun `设置页从首页进入后返回首页`() {
        navigation.openSettings()

        assertEquals(AppScreen.Settings, navigation.state.value.screen)

        navigation.closeSettings()
        assertEquals(AppScreen.Home, navigation.state.value.screen)
    }

    @Test
    fun `设置页从源列表进入后返回源列表`() {
        navigation.openProtocol(SourceProtocol.WebDav)
        navigation.openSettings()
        navigation.closeSettings()

        assertEquals(AppScreen.Sources, navigation.state.value.screen)
        assertEquals(SourceProtocol.WebDav, navigation.state.value.sourcesProtocol)
    }

    @Test
    fun `设置页从库页面进入后返回库页面`() {
        navigation.openProtocol(SourceProtocol.Local)
        navigation.openLibrary()
        navigation.openSettings()
        navigation.closeSettings()

        assertEquals(AppScreen.Library, navigation.state.value.screen)
    }

    @Test
    fun `首页第一次返回提示再按一次退出`() {
        assertEquals(HomeBackResult.ShowExitHint, navigation.onHomeBack())
    }

    @Test
    fun `首页两秒内再按一次退出`() {
        navigation.onHomeBack()
        now += NavigationViewModel.EXIT_WINDOW_MILLIS

        assertEquals(HomeBackResult.Exit, navigation.onHomeBack())
    }

    @Test
    fun `首页超过两秒再按只提示`() {
        navigation.onHomeBack()
        now += NavigationViewModel.EXIT_WINDOW_MILLIS + 1

        assertEquals(HomeBackResult.ShowExitHint, navigation.onHomeBack())
    }

    @Test
    fun `离开首页后返回计时重新开始`() {
        navigation.onHomeBack()
        navigation.openProtocol(SourceProtocol.Local)
        navigation.backToHome()

        assertEquals(HomeBackResult.ShowExitHint, navigation.onHomeBack())
    }

    @Test
    fun `进设置再返回后退出计时重新开始`() {
        navigation.onHomeBack()
        navigation.openSettings()
        navigation.closeSettings()

        assertEquals(HomeBackResult.ShowExitHint, navigation.onHomeBack())
    }

    @Test
    fun `界面重建后保持当前层级、协议与设置来处`() {
        navigation.openProtocol(SourceProtocol.WebDav)
        navigation.openLibrary()
        navigation.openSettings()

        val recreated = NavigationViewModel(savedState) { now }

        assertEquals(AppScreen.Settings, recreated.state.value.screen)
        assertEquals(SourceProtocol.WebDav, recreated.state.value.sourcesProtocol)

        recreated.closeSettings()
        assertEquals(AppScreen.Library, recreated.state.value.screen)
    }
}
