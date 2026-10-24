package com.cc3301.ccplayer.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.network.SourceProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** 应用内的界面层级。 */
enum class AppScreen { Home, Sources, Library, Settings }

/** 库页面所属连接源与目录栈，栈首为连接源根目录。 */
data class LibraryLocation(val sourceLocation: String, val stack: List<MediaFile>)

/** 导航状态：当前界面、源列表协议、设置页来处与库页面目录栈。 */
data class NavigationState(
    val screen: AppScreen = AppScreen.Home,
    val sourcesProtocol: SourceProtocol = SourceProtocol.Local,
    val settingsReturn: AppScreen = AppScreen.Home,
    val library: LibraryLocation? = null,
)

/** 首页返回的结果：第一次提示，窗口内再按一次退出。 */
enum class HomeBackResult { ShowExitHint, Exit }

/** 库页面系统返回的去向：回上级目录，或离开库页面回源列表。 */
enum class LibraryBackAction { NavigateUp, LeaveLibrary }

/**
 * 导航协调器：当前屏、源协议、设置页来处与库目录栈的唯一状态源。
 *
 * 每次变化把整份导航状态写入同一个快照：进程重建读 SavedStateHandle，
 * 冷启动读持久快照。当前屏为库页面时目录栈必须非空，恢复失败回首页并记录日志。
 */
class NavigationViewModel(
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val snapshots: NavigationSnapshotStore = NoNavigationSnapshotStore,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) : ViewModel() {

    /** 没有进程保存状态时等待冷启动恢复；用户先导航或恢复结束后清除。 */
    var coldStartPending: Boolean = !savedState.contains(KEY_SCREEN)
        private set

    /** 每次用户导航递增；库页面的冷启动校验据此取消。 */
    private val _userNavigationRevision = MutableStateFlow(0)
    val userNavigationRevision: StateFlow<Int> = _userNavigationRevision.asStateFlow()

    /** 构造期恢复失败可能早于诊断启用，先暂存，启用后补写。 */
    private val pendingDiagnostics = mutableListOf<String>()

    private val _state = MutableStateFlow(restoredState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    /** 导航快照按最新状态合并写入，失败不阻塞导航。 */
    private val snapshotWrites = MutableStateFlow<NavigationState?>(null)

    init {
        viewModelScope.launch {
            snapshotWrites.filterNotNull().collect { state ->
                try {
                    snapshots.save(state.toSnapshot())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    diagnostics.log { "导航快照写入失败: ${failure.javaClass.simpleName}" }
                }
            }
        }
    }

    private var lastExitHintAt: Long? = null

    fun openProtocol(protocol: SourceProtocol) {
        lastExitHintAt = null
        cancelColdStartRestore()
        update { it.copy(screen = AppScreen.Sources, sourcesProtocol = protocol, library = null) }
    }

    /** 进入库页面；目录栈必须非空。 */
    fun openLibrary(sourceLocation: String, stack: List<MediaFile>) {
        if (stack.isEmpty()) return
        cancelColdStartRestore()
        update {
            it.copy(screen = AppScreen.Library, library = LibraryLocation(sourceLocation, stack))
        }
    }

    /** 库页面进出目录后同步目录栈。 */
    fun updateLibrary(sourceLocation: String, stack: List<MediaFile>) {
        if (_state.value.screen != AppScreen.Library || stack.isEmpty()) return
        cancelColdStartRestore()
        update { it.copy(library = LibraryLocation(sourceLocation, stack)) }
    }

    fun openSettings() {
        lastExitHintAt = null
        cancelColdStartRestore()
        update {
            if (it.screen == AppScreen.Settings) it else it.copy(settingsReturn = it.screen, screen = AppScreen.Settings)
        }
    }

    /** 设置页返回进入前的界面；来处是库页面但目录栈为空时回首页。 */
    fun closeSettings() {
        cancelColdStartRestore()
        val target = _state.value.settingsReturn
        if (target == AppScreen.Library && _state.value.library?.stack.isNullOrEmpty()) {
            diagnostics.log { "导航: 设置页来处目录栈为空，回首页" }
            update { it.copy(screen = AppScreen.Home, library = null) }
        } else {
            update { it.copy(screen = target) }
        }
    }

    fun backToSources() {
        cancelColdStartRestore()
        update { it.copy(screen = AppScreen.Sources, library = null) }
    }

    /** 库页面系统返回：目录栈还有上级时交给库状态回退，否则回到源列表。 */
    fun backFromLibrary(): LibraryBackAction {
        cancelColdStartRestore()
        return if ((_state.value.library?.stack?.size ?: 0) > 1) {
            LibraryBackAction.NavigateUp
        } else {
            backToSources()
            LibraryBackAction.LeaveLibrary
        }
    }

    fun backToHome() {
        cancelColdStartRestore()
        update { it.copy(screen = AppScreen.Home, library = null) }
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

    /**
     * 冷启动恢复库页面：只在仍停在首页时生效。
     * 返回是否恢复；用户已先导航时丢弃本次恢复。
     */
    fun restoreColdStartLibrary(
        sourceLocation: String,
        sourcesProtocol: SourceProtocol,
        stack: List<MediaFile>,
    ): Boolean {
        if (!coldStartPending) return false
        coldStartPending = false
        if (_state.value.screen != AppScreen.Home || stack.isEmpty()) {
            diagnostics.log { "导航恢复: 当前已离开首页或目录栈为空，忽略冷启动恢复" }
            return false
        }
        update {
            it.copy(
                screen = AppScreen.Library,
                sourcesProtocol = sourcesProtocol,
                library = LibraryLocation(sourceLocation, stack),
            )
        }
        diagnostics.log { "导航恢复: 冷启动恢复库页面" }
        return true
    }

    /** 放弃冷启动恢复；[reason] 非空时记录可解释的原因。 */
    fun discardColdStart(reason: String? = null) {
        if (!coldStartPending) return
        coldStartPending = false
        if (reason != null) diagnostics.log { "导航恢复: $reason，回首页" }
    }

    /** 冷启动校验失败：只记录原因，不改变用户当前界面。 */
    fun failColdStart(reason: String) {
        coldStartPending = false
        logRestoreFailure(reason, returnsHome = _state.value.screen == AppScreen.Home)
    }

    /**
     * 已进入库页面后的读取失败：仅在仍停留在同一恢复目标时回首页。
     * 用户已离开时只记录日志并放弃。
     */
    fun failRestore(expected: LibraryLocation, reason: String) {
        coldStartPending = false
        val current = _state.value
        val sameTarget = current.screen == AppScreen.Library && current.library == expected
        logRestoreFailure(reason, returnsHome = sameTarget)
        if (sameTarget) {
            update { it.copy(screen = AppScreen.Home, library = null) }
        }
    }

    /** 诊断启用后补写构造期暂存的恢复日志。 */
    fun flushPendingDiagnostics() {
        if (!diagnostics.enabled || pendingDiagnostics.isEmpty()) return
        pendingDiagnostics.toList().forEach { message -> diagnostics.log(message) }
        pendingDiagnostics.clear()
    }

    private fun cancelColdStartRestore() {
        coldStartPending = false
        _userNavigationRevision.value += 1
    }

    private fun logRestoreFailure(reason: String, returnsHome: Boolean = true) {
        val suffix = if (returnsHome) "，回首页" else "，已放弃"
        val message = "导航恢复失败: $reason$suffix"
        if (diagnostics.enabled) {
            diagnostics.log(message)
        } else if (pendingDiagnostics.size < MAX_PENDING_DIAGNOSTICS) {
            pendingDiagnostics += message
        }
    }

    private fun update(transform: (NavigationState) -> NavigationState) {
        val previous = _state.value
        val updated = transform(previous)
        _state.value = updated
        if (updated == previous) return
        persistSavedState(updated)
        snapshotWrites.value = updated
    }

    private fun persistSavedState(state: NavigationState) {
        savedState[KEY_SCREEN] = state.screen.name
        savedState[KEY_SOURCES_PROTOCOL] = state.sourcesProtocol.name
        savedState[KEY_SETTINGS_RETURN] = state.settingsReturn.name
        val library = state.library
        if (library == null) {
            savedState.remove<String>(KEY_LIBRARY_SOURCE)
            savedState.remove<ArrayList<String>>(KEY_STACK_LOCATIONS)
            savedState.remove<ArrayList<String>>(KEY_STACK_NAMES)
        } else {
            savedState[KEY_LIBRARY_SOURCE] = library.sourceLocation
            savedState[KEY_STACK_LOCATIONS] = ArrayList(library.stack.map { it.location })
            savedState[KEY_STACK_NAMES] = ArrayList(library.stack.map { it.name })
        }
    }

    private fun restoredState(): NavigationState {
        if (!savedState.contains(KEY_SCREEN)) return NavigationState()
        val screen = savedState.enumValue(KEY_SCREEN, AppScreen.entries, AppScreen.Home)
        val protocol = savedState.enumValue(KEY_SOURCES_PROTOCOL, SourceProtocol.entries, SourceProtocol.Local)
        val settingsReturn = savedState.enumValue(KEY_SETTINGS_RETURN, AppScreen.entries, AppScreen.Home)
        val library = restoredLibrary()
        val invalidLibraryReturn = screen == AppScreen.Settings && settingsReturn == AppScreen.Library && library == null
        if ((screen == AppScreen.Library && library == null) || invalidLibraryReturn) {
            logRestoreFailure("库页面目录栈为空")
            return NavigationState(
                screen = AppScreen.Home,
                sourcesProtocol = protocol,
                settingsReturn = AppScreen.Home,
                library = null,
            )
        }
        return NavigationState(
            screen = screen,
            sourcesProtocol = protocol,
            settingsReturn = settingsReturn,
            library = library,
        )
    }

    private fun restoredLibrary(): LibraryLocation? {
        val source = savedState.get<String>(KEY_LIBRARY_SOURCE)?.takeIf { it.isNotBlank() } ?: return null
        val locations = savedState.get<ArrayList<String>>(KEY_STACK_LOCATIONS) ?: return null
        val names = savedState.get<ArrayList<String>>(KEY_STACK_NAMES) ?: return null
        if (locations.isEmpty() || locations.size != names.size) return null
        return LibraryLocation(
            sourceLocation = source,
            stack = locations.indices.map { index ->
                MediaFile(name = names[index], location = locations[index], isDirectory = true)
            },
        )
    }

    private fun <T : Enum<T>> SavedStateHandle.enumValue(key: String, entries: List<T>, default: T): T =
        entries.firstOrNull { it.name == get<String>(key) } ?: default

    private fun NavigationState.toSnapshot(): NavigationSnapshot = NavigationSnapshot(
        screen = screen,
        sourcesProtocol = sourcesProtocol,
        settingsReturn = settingsReturn,
        librarySource = library?.sourceLocation,
        libraryStack = library?.stack.orEmpty(),
    )

    companion object {
        const val EXIT_WINDOW_MILLIS = 2_000L

        private const val MAX_PENDING_DIAGNOSTICS = 8

        internal const val KEY_SCREEN = "navigation_screen"
        internal const val KEY_SOURCES_PROTOCOL = "navigation_sources_protocol"
        internal const val KEY_SETTINGS_RETURN = "navigation_settings_return"
        internal const val KEY_LIBRARY_SOURCE = "navigation_library_source"
        internal const val KEY_STACK_LOCATIONS = "navigation_stack_locations"
        internal const val KEY_STACK_NAMES = "navigation_stack_names"

        fun factory(context: Context, diagnostics: DiagnosticsLog = NoDiagnosticsLog): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    NavigationViewModel(
                        savedState = createSavedStateHandle(),
                        snapshots = DataStoreNavigationSnapshotStore.create(context.applicationContext),
                        diagnostics = diagnostics,
                    )
                }
            }
    }
}
