package com.cc3301.ccplayer.ui

import androidx.lifecycle.SavedStateHandle
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.network.SourceProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 记录导航恢复日志的测试出口。 */
private class RecordingDiagnostics : DiagnosticsLog {
    override var enabled: Boolean = true
    val messages = mutableListOf<String>()

    override fun log(message: String) {
        messages += message
    }
}

/** 快照保存失败，用于观察诊断日志。 */
private class FailingNavigationSnapshotStore : NavigationSnapshotStore {
    override suspend fun load(): NavigationSnapshot? = null

    override suspend fun save(snapshot: NavigationSnapshot) {
        throw IllegalStateException("磁盘不可写")
    }
}

/** 导航状态：逐级回退、设置页来处、首页二次返回窗口与一次快照恢复。 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    private var now = 0L
    private val savedState = SavedStateHandle()
    private val snapshots = InMemoryNavigationSnapshotStore()
    private val diagnostics = RecordingDiagnostics()

    private lateinit var navigation: NavigationViewModel

    private val root = MediaFile("media", "smb://nas/media", isDirectory = true)
    private val series = MediaFile("剧集", "smb://nas/media/剧集", isDirectory = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        navigation = NavigationViewModel(savedState, { now }, snapshots, diagnostics)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `默认停在首页`() {
        assertEquals(NavigationState(), navigation.state.value)
    }

    @Test
    fun `子目录到源列表到首页逐级回退`() {
        navigation.openProtocol(SourceProtocol.Smb)
        assertEquals(AppScreen.Sources, navigation.state.value.screen)
        assertEquals(SourceProtocol.Smb, navigation.state.value.sourcesProtocol)

        navigation.openLibrary(root.location, listOf(root))
        assertEquals(AppScreen.Library, navigation.state.value.screen)

        navigation.backToSources()
        assertEquals(AppScreen.Sources, navigation.state.value.screen)

        navigation.backToHome()
        assertEquals(AppScreen.Home, navigation.state.value.screen)
    }

    @Test
    fun `库页面有上级目录时回退上级且不离开库页面`() {
        navigation.openProtocol(SourceProtocol.Smb)
        navigation.openLibrary(root.location, listOf(root, series))

        assertEquals(LibraryBackAction.NavigateUp, navigation.backFromLibrary())
        assertEquals(AppScreen.Library, navigation.state.value.screen)
    }

    @Test
    fun `库页面在根目录时返回源列表`() {
        navigation.openProtocol(SourceProtocol.Smb)
        navigation.openLibrary(root.location, listOf(root))

        assertEquals(LibraryBackAction.LeaveLibrary, navigation.backFromLibrary())
        assertEquals(AppScreen.Sources, navigation.state.value.screen)
        assertNull(navigation.state.value.library)
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
        navigation.openLibrary(root.location, listOf(root, series))
        navigation.openSettings()
        navigation.closeSettings()

        assertEquals(AppScreen.Library, navigation.state.value.screen)
        assertEquals(listOf(root, series), navigation.state.value.library?.stack)
    }

    @Test
    fun `设置页来处的库目录栈为空时回首页`() {
        val invalid = SavedStateHandle(
            mapOf(
                NavigationViewModel.KEY_SCREEN to AppScreen.Settings.name,
                NavigationViewModel.KEY_SETTINGS_RETURN to AppScreen.Library.name,
            ),
        )

        val restored = NavigationViewModel(invalid, { now }, snapshots, diagnostics)

        assertEquals(AppScreen.Home, restored.state.value.screen)
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
    fun `界面重建后保持当前层级、协议、设置来处与库目录栈`() {
        navigation.openProtocol(SourceProtocol.WebDav)
        navigation.openLibrary(root.location, listOf(root, series))
        navigation.openSettings()

        val recreated = NavigationViewModel(restoredHandle(), { now }, snapshots, diagnostics)

        assertEquals(AppScreen.Settings, recreated.state.value.screen)
        assertEquals(SourceProtocol.WebDav, recreated.state.value.sourcesProtocol)
        assertEquals(listOf(root, series), recreated.state.value.library?.stack)

        recreated.closeSettings()
        assertEquals(AppScreen.Library, recreated.state.value.screen)
        assertEquals(listOf(root, series), recreated.state.value.library?.stack)
    }

    @Test
    fun `进程回收后设置页返回源列表`() {
        navigation.openProtocol(SourceProtocol.Smb)
        navigation.openSettings()

        val restored = restoredNavigation()

        assertEquals(AppScreen.Settings, restored.state.value.screen)

        restored.closeSettings()
        assertEquals(AppScreen.Sources, restored.state.value.screen)
        assertEquals(SourceProtocol.Smb, restored.state.value.sourcesProtocol)
    }

    @Test
    fun `源列表恢复时不被过期的设置来处影响`() {
        navigation.openProtocol(SourceProtocol.Smb)
        navigation.openLibrary(root.location, listOf(root))
        navigation.openSettings()
        navigation.closeSettings()
        navigation.backToSources()

        val restored = restoredNavigation()

        assertEquals(AppScreen.Sources, restored.state.value.screen)
        assertNull(restored.state.value.library)
    }

    @Test
    fun `当前屏为库页面但目录栈为空时回首页并记录日志`() {
        val emptyLibrary = SavedStateHandle(
            mapOf(
                NavigationViewModel.KEY_SCREEN to AppScreen.Library.name,
                NavigationViewModel.KEY_SOURCES_PROTOCOL to SourceProtocol.Smb.name,
                NavigationViewModel.KEY_SETTINGS_RETURN to AppScreen.Library.name,
            ),
        )

        val restored = NavigationViewModel(emptyLibrary, { now }, snapshots, diagnostics)

        assertEquals(AppScreen.Home, restored.state.value.screen)
        assertNull(restored.state.value.library)
        assertTrue(diagnostics.messages.any { it.contains("目录栈为空") })
    }

    @Test
    fun `没有保存状态时等待冷启动恢复`() {
        assertTrue(navigation.coldStartPending)
    }

    @Test
    fun `冷启动恢复库页面后清掉待恢复标记并写入快照`() = runTest {
        val applied = navigation.restoreColdStartLibrary(root.location, SourceProtocol.Smb, listOf(root, series))
        advanceUntilIdle()

        assertTrue(applied)
        assertFalse(navigation.coldStartPending)
        assertEquals(AppScreen.Library, navigation.state.value.screen)
        assertEquals(SourceProtocol.Smb, navigation.state.value.sourcesProtocol)
        assertEquals(listOf(root, series), navigation.state.value.library?.stack)
        assertEquals(AppScreen.Library, snapshots.load()?.screen)
        assertEquals(SourceProtocol.Smb, snapshots.load()?.sourcesProtocol)
        assertEquals(listOf(root, series), snapshots.load()?.libraryStack)
    }

    @Test
    fun `冷启动用户已离开首页时不再恢复`() {
        navigation.openProtocol(SourceProtocol.Local)

        val applied = navigation.restoreColdStartLibrary(root.location, SourceProtocol.Local, listOf(root))

        assertFalse(applied)
        assertEquals(AppScreen.Sources, navigation.state.value.screen)
        assertNull(navigation.state.value.library)
    }

    @Test
    fun `Home 到 Sources 再回 Home 后迟到的冷启动恢复不覆盖`() {
        navigation.openProtocol(SourceProtocol.Local)
        navigation.backToHome()

        val applied = navigation.restoreColdStartLibrary(root.location, SourceProtocol.Local, listOf(root))

        assertFalse(applied)
        assertFalse(navigation.coldStartPending)
        assertEquals(AppScreen.Home, navigation.state.value.screen)
        assertNull(navigation.state.value.library)
    }

    @Test
    fun `导航状态一次写入同一份快照`() = runTest {
        navigation.openProtocol(SourceProtocol.Smb)
        navigation.openLibrary(root.location, listOf(root, series))
        navigation.openSettings()
        advanceUntilIdle()

        val snapshot = snapshots.load()
        assertEquals(AppScreen.Settings, snapshot?.screen)
        assertEquals(SourceProtocol.Smb, snapshot?.sourcesProtocol)
        assertEquals(AppScreen.Library, snapshot?.settingsReturn)
        assertEquals(root.location, snapshot?.librarySource)
        assertEquals(listOf(root, series), snapshot?.libraryStack)
    }

    @Test
    fun `恢复失败回首页并记录原因`() = runTest {
        navigation.openLibrary(root.location, listOf(root))
        val target = LibraryLocation(root.location, listOf(root))

        navigation.failRestore(target, "目录不可访问")

        assertEquals(AppScreen.Home, navigation.state.value.screen)
        assertNull(navigation.state.value.library)
        assertTrue(diagnostics.messages.any { it.contains("导航恢复失败") && it.contains("目录不可访问") })
    }

    @Test
    fun `用户已离开后恢复失败只记录日志不回首页`() = runTest {
        navigation.openLibrary(root.location, listOf(root))
        val target = LibraryLocation(root.location, listOf(root))
        navigation.backToSources()

        navigation.failRestore(target, "目录不可访问")

        assertEquals(AppScreen.Sources, navigation.state.value.screen)
        assertNull(navigation.state.value.library)
        assertTrue(diagnostics.messages.any { it.contains("导航恢复失败") })
    }

    @Test
    fun `快照写入失败记录诊断日志`() = runTest {
        val recording = RecordingDiagnostics()
        val failing = NavigationViewModel(
            SavedStateHandle(),
            { now },
            FailingNavigationSnapshotStore(),
            recording,
        )

        failing.openProtocol(SourceProtocol.Local)
        advanceUntilIdle()

        assertTrue(recording.messages.any { it.contains("导航快照写入失败") })
    }

    @Test
    fun `诊断启用后补写构造期恢复失败日志`() {
        val recording = RecordingDiagnostics().apply { enabled = false }
        val invalid = SavedStateHandle(
            mapOf(NavigationViewModel.KEY_SCREEN to AppScreen.Library.name),
        )
        val restored = NavigationViewModel(invalid, { now }, snapshots, recording)

        assertTrue(recording.messages.isEmpty())

        recording.enabled = true
        restored.flushPendingDiagnostics()

        assertTrue(recording.messages.any { it.contains("目录栈为空") })
    }

    private fun restoredNavigation(): NavigationViewModel =
        NavigationViewModel(restoredHandle(), { now }, snapshots, diagnostics)

    /**
     * 模拟进程回收：新建句柄并深拷贝列表值。
     * 直接构造 SavedStateHandle 不经过 Bundle 序列化，只覆盖键值进入恢复路径。
     */
    private fun restoredHandle(): SavedStateHandle {
        val restored = SavedStateHandle(
            savedState.keys().associateWith { key ->
                when (val value = savedState.get<Any?>(key)) {
                    is ArrayList<*> -> ArrayList(value)
                    else -> value
                }
            },
        )
        return restored
    }
}
