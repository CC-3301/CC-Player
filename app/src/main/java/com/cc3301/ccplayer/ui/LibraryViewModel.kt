package com.cc3301.ccplayer.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cc3301.ccplayer.diagnostics.DiagnosticsLog
import com.cc3301.ccplayer.diagnostics.NoDiagnosticsLog
import com.cc3301.ccplayer.library.DirectoryMediaLibrary
import com.cc3301.ccplayer.library.DirectoryPosition
import com.cc3301.ccplayer.library.DirectoryPositionStore
import com.cc3301.ccplayer.library.DirectoryScrollTarget
import com.cc3301.ccplayer.library.DataStoreDirectoryPositionStore
import com.cc3301.ccplayer.library.MediaFailure
import com.cc3301.ccplayer.library.MediaFile
import com.cc3301.ccplayer.library.MediaFileTree
import com.cc3301.ccplayer.library.MediaLibrary
import com.cc3301.ccplayer.library.MediaSourceException
import com.cc3301.ccplayer.library.ProgressCleanupMediaLibrary
import com.cc3301.ccplayer.library.RoutingMediaFileTree
import com.cc3301.ccplayer.library.SafMediaFileTree
import com.cc3301.ccplayer.library.SortOrder
import com.cc3301.ccplayer.library.directoryScrollTarget
import com.cc3301.ccplayer.library.relativeDirectoryPath
import com.cc3301.ccplayer.library.sortedForDisplay
import com.cc3301.ccplayer.network.DataStoreBookmarkStore
import com.cc3301.ccplayer.network.EncryptedCredentialStore
import com.cc3301.ccplayer.network.NetworkBookmark
import com.cc3301.ccplayer.network.NetworkSourceRepository
import com.cc3301.ccplayer.network.NetworkSourceVerifier
import com.cc3301.ccplayer.network.RoutingNetworkSourceVerifier
import com.cc3301.ccplayer.network.SourceProtocol
import com.cc3301.ccplayer.network.normalizeRelativePath
import com.cc3301.ccplayer.progress.RoomPlaybackProgressRepository
import com.cc3301.ccplayer.settings.SettingsRepository
import com.cc3301.ccplayer.settings.ViewMode
import com.cc3301.ccplayer.smb.SmbAddress
import com.cc3301.ccplayer.smb.SmbJConnector
import com.cc3301.ccplayer.smb.SmbLocation
import com.cc3301.ccplayer.smb.SmbLocator
import com.cc3301.ccplayer.smb.SmbMediaFileTree
import com.cc3301.ccplayer.smb.SmbSourceVerifier
import com.cc3301.ccplayer.webdav.OkHttpWebDavTransport
import com.cc3301.ccplayer.webdav.WebDavAddress
import com.cc3301.ccplayer.webdav.WebDavLocator
import com.cc3301.ccplayer.webdav.WebDavMediaFileTree
import com.cc3301.ccplayer.webdav.WebDavSourceVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一条连接源：源列表展示 [name]，进入库页面时以 [root] 为根目录。 */
data class ConnectionSource(
    val protocol: SourceProtocol,
    val name: String,
    /** 持久化位置：本地是 SAF 树 URI，网络源是书签地址。 */
    val location: String,
    val root: MediaFile,
) {
    /** 只有网络源可以编辑；本地连接源只提供删除。 */
    val editable: Boolean get() = protocol != SourceProtocol.Local
}

/** 新增或编辑网络源的表单状态。 */
data class NetworkSourceForm(
    val protocol: SourceProtocol,
    /** 编辑时的原地址；null 表示新增。 */
    val originalUrl: String? = null,
    val name: String = "",
    val address: String = "",
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val saving: Boolean = false,
    /** 服务器地址缺失或格式不对。 */
    val addressInvalid: Boolean = false,
    /** 编辑后的地址与另一条连接源重复。 */
    val duplicateAddress: Boolean = false,
    /** 上次连通性校验失败的原因。 */
    val failure: MediaFailure? = null,
) {
    val editing: Boolean get() = originalUrl != null
}

/** 首页与源列表的状态。 */
data class SourcesState(
    val ready: Boolean = false,
    val local: List<ConnectionSource> = emptyList(),
    val smb: List<ConnectionSource> = emptyList(),
    val webDav: List<ConnectionSource> = emptyList(),
    val form: NetworkSourceForm? = null,
) {
    fun sources(protocol: SourceProtocol): List<ConnectionSource> = when (protocol) {
        SourceProtocol.Local -> local
        SourceProtocol.Smb -> smb
        SourceProtocol.WebDav -> webDav
    }
}

/** 库页面的状态。 */
data class LibraryState(
    /** 当前连接源的持久化位置；用于保存目录位置记录。 */
    val sourceLocation: String = "",
    /** 浏览路径，最后一项是当前目录；为空表示停在根目录这一层。 */
    val stack: List<MediaFile> = emptyList(),
    /** 当前目录读出的条目，未排序；展示次序见 [visibleEntries]。 */
    val entries: List<MediaFile> = emptyList(),
    val viewMode: ViewMode = ViewMode.List,
    val gridColumns: Int = SettingsRepository.DEFAULT_GRID_COLUMNS,
    val sortOrder: SortOrder = SettingsRepository.DEFAULT_SORT_ORDER,
    /** "进入目录时自动回到上次停留的位置"开关，默认开。 */
    val autoRestorePosition: Boolean = SettingsRepository.DEFAULT_AUTO_RESTORE_POSITION,
    /** 进程恢复的目录首次读取按位置记录定位，不受位置记忆开关影响。 */
    val restorePositionForced: Boolean = false,
    /** 当前目录已保存的位置记录；没有记录时为 null。 */
    val savedPosition: DirectoryPosition? = null,
    /** 本次要滚动到的条目与偏移；空目录时为 null。 */
    val scrollTarget: DirectoryScrollTarget? = null,
    /** 每次定出滚动目标时递增：界面据此滚动一次。 */
    val scrollRevision: Int = 0,
    /** 加载期间切换了排序：目录读完后回到顶部，而不是按旧记录定位。 */
    val pendingScrollToTop: Boolean = false,
    val loading: Boolean = false,
    /** 当前目录读取失败的原因；成功时为 null。 */
    val failure: MediaFailure? = null,
) {
    val currentDirectory: MediaFile? get() = stack.lastOrNull()

    /** 列表实际展示的次序：子目录在前，组内按当前排序选项。 */
    val visibleEntries: List<MediaFile> get() = entries.sortedForDisplay(sortOrder)

    /**
     * 套用展示偏好：排序变化回到顶部；切换视图始终按位置记录定位；
     * 首次套用偏好时若有取值变化，按实际开关重算一次。
     * 之后就地切换开关不改变当前目录已定的滚动目标。
     */
    fun withDisplayPreferences(
        viewMode: ViewMode,
        gridColumns: Int,
        sortOrder: SortOrder,
        autoRestorePosition: Boolean,
        sortChangeScrollsToTop: Boolean = true,
        recomputePositionOnFirstApply: Boolean = false,
    ): LibraryState {
        val viewChanged = viewMode != this.viewMode || gridColumns != this.gridColumns
        val sortChanged = sortChangeScrollsToTop && sortOrder != this.sortOrder
        val preferencesChanged = viewChanged ||
            sortOrder != this.sortOrder ||
            autoRestorePosition != this.autoRestorePosition
        val updated = copy(
            viewMode = viewMode,
            gridColumns = gridColumns,
            sortOrder = sortOrder,
            autoRestorePosition = autoRestorePosition,
        )
        return when {
            sortChanged -> updated.scrolledToTop(pendingWhileLoading = updated.loading)
            recomputePositionOnFirstApply && preferencesChanged -> updated.retargeted()
            // 加载中条目已清空，先记下强制定位意图，等 withLoadedEntries 读完目录再定位。
            viewChanged && updated.loading -> updated.copy(restorePositionForced = true)
            viewChanged -> updated.retargeted(restorePosition = true)
            else -> updated
        }
    }

    /** 目录读完后套用结果，并按位置记录定出滚动目标。 */
    fun withLoadedEntries(
        entries: List<MediaFile>,
        failure: MediaFailure?,
        position: DirectoryPosition?,
    ): LibraryState {
        val loaded = copy(
            entries = entries,
            loading = false,
            failure = failure,
            savedPosition = position,
        )
        return if (pendingScrollToTop) {
            val topped = loaded.scrolledToTop()
            topped.copy(
                pendingScrollToTop = false,
                savedPosition = topped.visibleEntries.firstOrNull()?.let { DirectoryPosition(it.location, 0) },
            )
        } else {
            loaded.retargeted()
        }
    }

    /** 按当前可见次序重新计算滚动目标并递增序号。 */
    private fun retargeted(
        restorePosition: Boolean = autoRestorePosition || restorePositionForced,
    ): LibraryState = copy(
        scrollTarget = directoryScrollTarget(visibleEntries, savedPosition, restorePosition),
        scrollRevision = scrollRevision + 1,
    )

    /** 回到顶部：按正常滚动规则把第一个可见条目记为当前位置。 */
    fun scrolledToTop(pendingWhileLoading: Boolean = false): LibraryState = copy(
        scrollTarget = if (visibleEntries.isEmpty()) null else DirectoryScrollTarget(index = 0, offset = 0),
        scrollRevision = scrollRevision + 1,
        pendingScrollToTop = pendingWhileLoading,
        // 用户主动排序回到顶部，不再按进程恢复目标定位。
        restorePositionForced = false,
        savedPosition = if (pendingWhileLoading) {
            savedPosition
        } else {
            visibleEntries.firstOrNull()?.let { DirectoryPosition(it.location, 0) } ?: savedPosition
        },
    )
}

/**
 * 应用状态持有者：管理本地与网络连接源、网络源表单，以及单个连接源的目录浏览。
 *
 * 外挂字幕发现需要当前目录的原始条目（字幕文件不在媒体列表里），因此同时持有 [files]。
 */
class LibraryViewModel(
    private val library: MediaLibrary,
    private val files: MediaFileTree,
    private val settings: SettingsRepository,
    private val sources: NetworkSourceRepository,
    private val verifier: NetworkSourceVerifier,
    private val positions: DirectoryPositionStore,
    private val navigation: NavigationViewModel,
    private val snapshots: NavigationSnapshotStore = NoNavigationSnapshotStore,
    private val diagnostics: DiagnosticsLog = NoDiagnosticsLog,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _sourcesState = MutableStateFlow(SourcesState())
    val sourcesState: StateFlow<SourcesState> = _sourcesState.asStateFlow()

    private var loadJob: Job? = null

    /** 首次偏好读取只套用取值，不把排序差异当成用户切换。 */
    private var displayPreferencesApplied = false

    /** 正在按导航快照恢复：首次读取失败时回首页，而不是停在库页面的错误页。 */
    private var restoringNavigation = false

    /** 本次恢复的目标；用户离开后不再据此回首页。 */
    private var restoreTarget: LibraryLocation? = null

    /** 冷启动校验任务；用户导航时取消。 */
    private var restoreJob: Job? = null

    /** 恢复后的首次目录读取；离开库页面或设置页时取消。 */
    private var restoreLoadJob: Job? = null

    /** 本次恢复已被用户放弃；只记录一次。 */
    private var restoreAbandonLogged = false

    init {
        viewModelScope.launch {
            combine(settings.rootLocations, sources.bookmarks) { local, network -> local to network }
                .collect { (local, network) ->
                    _sourcesState.update { current ->
                        current.copy(
                            ready = true,
                            local = local.mapNotNull { resolveLocalSource(it) },
                            smb = network.filter { it.protocol == SourceProtocol.Smb }.mapNotNull { resolveNetworkSource(it) },
                            webDav = network.filter { it.protocol == SourceProtocol.WebDav }.mapNotNull { resolveNetworkSource(it) },
                        )
                    }
                }
        }
        viewModelScope.launch {
            combine(
                settings.viewMode,
                settings.gridColumns,
                settings.sortOrder,
                settings.autoRestorePosition,
            ) { mode, columns, order, autoRestore ->
                ListPreferences(mode, columns, order, autoRestore)
            }.collect { preferences ->
                val firstApply = !displayPreferencesApplied
                displayPreferencesApplied = true
                _state.update { current ->
                    consumeForcedRestore(
                        current.withDisplayPreferences(
                            viewMode = preferences.viewMode,
                            gridColumns = preferences.gridColumns,
                            sortOrder = preferences.sortOrder,
                            autoRestorePosition = preferences.autoRestorePosition,
                            sortChangeScrollsToTop = !firstApply,
                            recomputePositionOnFirstApply = firstApply,
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            navigation.userNavigationRevision.drop(1).collect { abandonNavigationRestore() }
        }
        viewModelScope.launch {
            navigation.state.collect { state ->
                val inLibraryArea = state.screen == AppScreen.Library || state.screen == AppScreen.Settings
                // 冷启动校验阶段发生在首页，由 userNavigationRevision 负责取消。
                if (!inLibraryArea && (restoringNavigation || restoreLoadJob != null)) {
                    abandonNavigationRestore()
                }
            }
        }
        restoreNavigation()
    }

    /** 本地文件夹选择器返回后调用。 */
    fun addLocalRoot(location: String) {
        viewModelScope.launch { settings.addRoot(location) }
    }

    /** 删除连接源：先清理位置记录，再移除本地记录与凭据；不动远程文件或本地文件夹。 */
    fun removeSource(source: ConnectionSource) {
        viewModelScope.launch {
            positions.clearSource(source.location)
            if (source.protocol == SourceProtocol.Local) {
                settings.removeRoot(source.location)
            } else {
                sources.remove(source.location)
            }
        }
    }

    /** 打开新增网络源表单；本地连接源不需要表单。 */
    fun openNewSourceForm(protocol: SourceProtocol) {
        if (protocol == SourceProtocol.Local) return
        _sourcesState.update { it.copy(form = NetworkSourceForm(protocol = protocol)) }
    }

    /** 打开编辑表单并带出已保存的字段与密码。 */
    fun openEditSourceForm(source: ConnectionSource) {
        if (!source.editable) return
        viewModelScope.launch {
            val bookmark = sources.bookmarks.first().firstOrNull { it.url == source.location } ?: return@launch
            val password = sources.password(bookmark.url).orEmpty()
            _sourcesState.update { current ->
                if (current.form != null) {
                    current
                } else {
                    current.copy(
                        form = NetworkSourceForm(
                            protocol = bookmark.protocol,
                            originalUrl = bookmark.url,
                            name = bookmark.name,
                            address = bookmark.address,
                            path = bookmark.path,
                            username = bookmark.username,
                            password = password,
                        ),
                    )
                }
            }
        }
    }

    fun dismissNetworkSourceForm() {
        _sourcesState.update { current ->
            if (current.form?.saving == true) current else current.copy(form = null)
        }
    }

    /** 离开源列表或切换协议时清掉表单，避免把旧表单带进另一协议。 */
    fun clearNetworkSourceForm() {
        _sourcesState.update { it.copy(form = null) }
    }

    /** 按字段本身更新表单，不依赖字段在界面上的位置。 */
    fun updateFormField(kind: NetworkSourceFieldKind, value: String) {
        _sourcesState.update { current ->
            val form = current.form ?: return@update current
            val updated = when (kind) {
                NetworkSourceFieldKind.Name -> form.copy(name = value)
                NetworkSourceFieldKind.Address -> form.copy(address = value)
                NetworkSourceFieldKind.Path -> form.copy(path = value)
                NetworkSourceFieldKind.Username -> form.copy(username = value)
                NetworkSourceFieldKind.Password -> form.copy(password = value)
            }
            current.copy(form = updated.copy(addressInvalid = false, duplicateAddress = false, failure = null))
        }
    }

    /** 校验地址并试连后保存；地址不合法、重复或连不上时保留表单并给出原因。 */
    fun saveNetworkSourceForm() {
        val form = _sourcesState.value.form ?: return
        if (form.saving) return
        val bookmark = normalizedBookmark(form)
        if (bookmark == null) {
            _sourcesState.update { current ->
                current.copy(form = current.form?.copy(addressInvalid = true, duplicateAddress = false, failure = null))
            }
            return
        }
        viewModelScope.launch {
            val duplicate = form.editing && sources.bookmarks.first().any { it.url == bookmark.url && it.url != form.originalUrl }
            if (duplicate) {
                _sourcesState.update { current ->
                    current.copy(form = current.form?.copy(duplicateAddress = true, failure = null))
                }
                return@launch
            }
            _sourcesState.update { current ->
                current.copy(form = current.form?.copy(saving = true, addressInvalid = false, duplicateAddress = false, failure = null))
            }
            val failure = verify(bookmark.url, bookmark.username, form.password)
            if (failure != null) {
                _sourcesState.update { current ->
                    current.copy(form = current.form?.copy(saving = false, failure = failure))
                }
                return@launch
            }
            try {
                if (form.originalUrl != null) {
                    sources.update(previousUrl = form.originalUrl, bookmark = bookmark, password = form.password)
                } else {
                    sources.add(bookmark = bookmark, password = form.password)
                }
            } catch (duplicate: IllegalArgumentException) {
                _sourcesState.update { current ->
                    current.copy(form = current.form?.copy(saving = false, duplicateAddress = true))
                }
                return@launch
            }
            _sourcesState.update { it.copy(form = null) }
        }
    }

    /** 进入某个连接源的库页面，根目录为该连接源的根。 */
    fun openSource(source: ConnectionSource) {
        cancelNavigationRestore()
        val stack = listOf(source.root)
        _state.update {
            it.copy(
                sourceLocation = source.location,
                stack = stack,
                entries = emptyList(),
                loading = true,
                failure = null,
                savedPosition = null,
                scrollTarget = null,
                pendingScrollToTop = false,
                restorePositionForced = false,
            )
        }
        navigation.openLibrary(source.location, stack)
        load(source.root)
    }

    fun enter(directory: MediaFile) {
        cancelNavigationRestore()
        val stack = _state.value.stack + directory
        _state.update {
            it.copy(
                stack = stack,
                entries = emptyList(),
                restorePositionForced = false,
            )
        }
        navigation.updateLibrary(_state.value.sourceLocation, stack)
        load(directory)
    }

    /** 回到上一级；停在连接源根目录时不动，由界面离开库页面。 */
    fun navigateUp() {
        val stack = _state.value.stack
        if (stack.size <= 1) return
        cancelNavigationRestore()
        val parent = stack.dropLast(1).last()
        val updatedStack = stack.dropLast(1)
        _state.update {
            it.copy(
                stack = updatedStack,
                entries = emptyList(),
                restorePositionForced = false,
            )
        }
        navigation.updateLibrary(_state.value.sourceLocation, updatedStack)
        load(parent)
    }

    /** 长按排序按钮：按状态目标回到顶部，排序不变，位置按正常滚动规则记录。 */
    fun scrollToTop() {
        val current = _state.value
        _state.update { it.scrolledToTop(pendingWhileLoading = it.loading) }
        val topEntry = current.visibleEntries.firstOrNull() ?: return
        savePosition(current.sourceLocation, relativeDirectoryPath(current.stack), topEntry.location, 0)
    }

    /** 重新读取当前目录。 */
    fun retry() {
        _state.value.currentDirectory?.let { load(it) }
    }

    /** 当前目录的原始条目：媒体列表过滤掉的字幕文件也在；停在根目录或读不出来时为空。 */
    suspend fun directoryEntries(): List<MediaFile> {
        val directory = _state.value.currentDirectory ?: return emptyList()
        return try {
            files.children(directory)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            emptyList()
        }
    }

    /** 记录某个目录的第一个可见条目与滚动偏移；位置记录与位置记忆开关无关，加载中或条目不在当前列表时不写。 */
    fun savePosition(sourceLocation: String, relativePath: String, entryLocation: String, scrollOffset: Int) {
        if (sourceLocation.isBlank()) return
        val current = _state.value
        if (current.loading || current.visibleEntries.none { it.location == entryLocation }) return
        val position = DirectoryPosition(entryLocation, scrollOffset)
        _state.update { current ->
            if (current.sourceLocation == sourceLocation && relativeDirectoryPath(current.stack) == relativePath) {
                val index = current.visibleEntries.indexOfFirst { it.location == entryLocation }
                current.copy(
                    savedPosition = position,
                    // 同步目标但不递增序号：离开再返回时据此定位，正常浏览时不触发滚动。
                    scrollTarget = if (index >= 0) DirectoryScrollTarget(index, scrollOffset) else current.scrollTarget,
                )
            } else {
                current
            }
        }
        viewModelScope.launch {
            positions.save(sourceLocation, relativePath, position)
        }
    }

    private fun load(directory: MediaFile, preloadedEntries: List<MediaFile>? = null): Job {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, failure = null) }
        val job = viewModelScope.launch {
            val sourceLocation = _state.value.sourceLocation
            val relativePath = relativeDirectoryPath(_state.value.stack)
            val position = loadPosition(sourceLocation, relativePath)
            val result = preloadedEntries?.let { Result.success(it) } ?: library.list(directory)
            currentCoroutineContext().ensureActive()
            _state.update {
                consumeForcedRestore(
                    it.withLoadedEntries(result.getOrDefault(emptyList()), result.exceptionOrNull().toFailure(), position),
                )
            }
            finishNavigationRestore()
        }
        loadJob = job
        return job
    }

    /**
     * 进程恢复的强制定位在首次成功读取且显示偏好已套用后消费。
     * 读取失败时保留，retry 仍可强制定位。
     */
    private fun consumeForcedRestore(state: LibraryState): LibraryState =
        if (state.restorePositionForced && !state.loading && state.failure == null && displayPreferencesApplied) {
            state.copy(restorePositionForced = false)
        } else {
            state
        }

    /** 位置记录读取失败按没有记录处理，不阻断目录浏览。 */
    private suspend fun loadPosition(sourceLocation: String, relativePath: String): DirectoryPosition? = try {
        positions.load(sourceLocation, relativePath)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }

    /** 本地源解析不出来（比如授权失效）时仍列出，好让用户能删除它。 */
    private suspend fun resolveLocalSource(location: String): ConnectionSource {
        val root = runCatching { library.resolveDirectory(location) }.getOrNull()
            ?: MediaFile(
                name = location.substringAfterLast('/').ifBlank { location },
                location = location,
                isDirectory = true,
            )
        return ConnectionSource(protocol = SourceProtocol.Local, name = root.name, location = location, root = root)
    }

    private suspend fun resolveNetworkSource(bookmark: NetworkBookmark): ConnectionSource? =
        runCatching { library.resolveDirectory(bookmark.url) }.getOrNull()?.let { root ->
            ConnectionSource(
                protocol = bookmark.protocol,
                name = bookmark.displayName,
                location = bookmark.url,
                root = root.copy(name = bookmark.displayName),
            )
        }

    private fun normalizedBookmark(form: NetworkSourceForm): NetworkBookmark? {
        val address = when (form.protocol) {
            SourceProtocol.Smb -> SmbAddress.normalize(form.address)
            SourceProtocol.WebDav -> WebDavAddress.normalize(form.address)
            SourceProtocol.Local -> null
        } ?: return null
        return NetworkBookmark(
            protocol = form.protocol,
            name = form.name.trim(),
            address = address,
            path = normalizeRelativePath(form.path),
            username = form.username,
        )
    }

    private suspend fun verify(url: String, username: String, password: String): MediaFailure? = try {
        verifier.verify(url, username, password)
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (problem: Exception) {
        problem.toFailure()
    }

    private fun Throwable?.toFailure(): MediaFailure? = when (this) {
        null -> null
        is MediaSourceException -> failure
        else -> MediaFailure.Unknown
    }

    /** 按导航协调器的快照恢复：进程重建直接用当前状态，冷启动读持久快照。 */
    private fun restoreNavigation() {
        restoreAbandonLogged = false
        restoreJob = viewModelScope.launch {
            when {
                // 设置页来处是库页面时也先恢复目录栈，返回时直接可用。
                navigation.state.value.library != null -> restoreSavedLibrary()
                navigation.coldStartPending.value -> restoreColdStart()
            }
        }
    }

    private fun restoreSavedLibrary() {
        val library = navigation.state.value.library
        if (library == null || library.stack.isEmpty()) {
            navigation.failColdStart("库页面目录栈为空")
            return
        }
        applyRestoredLibrary(library)
    }

    /**
     * 冷启动：设置页不作落点，按设置页来处决定落到库页面、源列表或首页。
     * 开关关闭或没有快照时保持首页；快照无法解析、连接源删除或目录不可访问时回首页并记录原因。
     */
    private suspend fun restoreColdStart() {
        // 开关关闭时立即结束判定，不必等连接源解析（可能连网）。
        if (!settings.restoreLastDirectory.first()) {
            navigation.discardColdStart()
            return
        }
        val loaded = try {
            snapshots.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            NavigationSnapshotLoad.Unreadable
        }
        val snapshot = when (loaded) {
            is NavigationSnapshotLoad.Loaded -> loaded.snapshot
            // 首次启动没有写过快照，不属于恢复失败。
            NavigationSnapshotLoad.Absent -> {
                navigation.discardColdStart()
                return
            }
            NavigationSnapshotLoad.Unreadable -> {
                navigation.failColdStart("导航快照不可用")
                return
            }
        }
        // 设置页不是恢复目标：先把来处归一到库页面、源列表或首页，再决定落点。
        val fromSettings = snapshot.screen == AppScreen.Settings
        when (if (fromSettings) snapshot.settingsReturn else snapshot.screen) {
            AppScreen.Library -> Unit
            AppScreen.Sources -> {
                // 只有设置页来处才恢复源列表；直接停在源列表不属于恢复目标。
                if (fromSettings) {
                    navigation.restoreColdStartSources(snapshot.sourcesProtocol)
                } else {
                    navigation.discardColdStart("上次停留界面是源列表")
                }
                return
            }
            AppScreen.Home -> {
                navigation.discardColdStart(
                    if (fromSettings) "上次停留界面是设置页，来处是首页" else "上次停留界面是首页",
                )
                return
            }
            // 来处记录成设置页，属快照内容非法。
            AppScreen.Settings -> {
                navigation.failColdStart("上次停留界面是设置页，来处非法")
                return
            }
        }
        val sourceLocation = snapshot.librarySource
        if (sourceLocation.isNullOrBlank() || snapshot.libraryStack.isEmpty()) {
            navigation.failColdStart("库页面目录栈为空")
            return
        }
        val sources = sourcesState.filter { it.ready }.first()
        val source = (sources.local + sources.smb + sources.webDav)
            .firstOrNull { it.location == sourceLocation }
        if (source == null) {
            navigation.failColdStart("连接源不可用")
            return
        }
        val validated = validateStack(source, snapshot.libraryStack)
        if (validated == null) {
            navigation.failColdStart("目录不可访问")
            return
        }
        if (!navigation.restoreColdStartLibrary(source.location, snapshot.sourcesProtocol, validated.stack)) return
        applyRestoredLibrary(
            LibraryLocation(source.location, validated.stack),
            preloadedEntries = validated.entries,
        )
    }

    /** 逐级校验并重建目录栈；已读到的当前目录条目留给首次加载复用。 */
    private suspend fun validateStack(source: ConnectionSource, savedStack: List<MediaFile>): ValidatedLibrary? {
        if (savedStack.firstOrNull()?.location != source.root.location) return null
        val stack = mutableListOf(source.root)
        for (index in 1 until savedStack.size) {
            val children = library.list(stack.last()).getOrElse { return null }
            val child = children.firstOrNull { it.isDirectory && it.location == savedStack[index].location }
                ?: return null
            stack += child
        }
        val entries = library.list(stack.last()).getOrElse { return null }
        return ValidatedLibrary(stack = stack, entries = entries)
    }

    /** 按快照设置目录栈并读取当前目录；首次读取的浏览位置不受目录位置记忆开关影响。 */
    private fun applyRestoredLibrary(
        library: LibraryLocation,
        preloadedEntries: List<MediaFile>? = null,
    ) {
        restoringNavigation = true
        restoreTarget = library
        _state.update {
            it.copy(
                sourceLocation = library.sourceLocation,
                stack = library.stack,
                entries = emptyList(),
                loading = true,
                failure = null,
                savedPosition = null,
                scrollTarget = null,
                pendingScrollToTop = false,
                restorePositionForced = true,
            )
        }
        restoreLoadJob = load(library.stack.last(), preloadedEntries)
    }

    /** 恢复读取失败时只在仍停留于同一恢复目标时回首页。 */
    private fun finishNavigationRestore() {
        if (!restoringNavigation) return
        val target = restoreTarget
        restoringNavigation = false
        restoreTarget = null
        restoreLoadJob = null
        if (target != null && _state.value.failure != null) {
            navigation.failRestore(target, "目录不可访问")
        }
    }

    /** 用户已开始新的导航：取消恢复校验与派生加载，放弃原恢复目标。 */
    private fun cancelNavigationRestore() {
        abandonNavigationRestore()
    }

    /** 用户导航导致恢复未完成时，取消任务并记录一次「已放弃本次恢复」。 */
    private fun abandonNavigationRestore() {
        val active = restoringNavigation || restoreJob?.isActive == true || restoreLoadJob?.isActive == true
        if (!active) return
        restoreJob?.cancel()
        restoreJob = null
        restoreLoadJob?.cancel()
        restoreLoadJob = null
        restoringNavigation = false
        restoreTarget = null
        if (!restoreAbandonLogged) {
            restoreAbandonLogged = true
            diagnostics.log { "导航恢复: 已放弃本次恢复" }
        }
    }

    /** 校验结果：重建后的目录栈与当前目录已读出的条目。 */
    private data class ValidatedLibrary(val stack: List<MediaFile>, val entries: List<MediaFile>)

    /** 影响列表展示与滚动的设置项。 */
    private data class ListPreferences(
        val viewMode: ViewMode,
        val gridColumns: Int,
        val sortOrder: SortOrder,
        val autoRestorePosition: Boolean,
    )

    companion object {
        /** 生产入口：本地 SAF 与各网络协议的文件树（列举目录后清已消失媒体的进度记录）+ DataStore 设置与书签。 */
        fun factory(
            context: Context,
            navigation: NavigationViewModel,
            diagnostics: DiagnosticsLog = NoDiagnosticsLog,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                val sources = NetworkSourceRepository(
                    store = DataStoreBookmarkStore.create(appContext),
                    credentials = EncryptedCredentialStore.create(appContext),
                )
                val smbConnector = SmbJConnector()
                val smbLocator = SmbLocator(sources)
                val webDavTransport = OkHttpWebDavTransport()
                val webDavLocator = WebDavLocator(sources)
                val tree = RoutingMediaFileTree(
                    mapOf(
                        RoutingMediaFileTree.SAF_SCHEME to SafMediaFileTree(appContext),
                        SmbLocation.SCHEME to SmbMediaFileTree(smbConnector, smbLocator),
                        WebDavAddress.SCHEME_HTTP to WebDavMediaFileTree(webDavTransport, webDavLocator),
                        WebDavAddress.SCHEME_HTTPS to WebDavMediaFileTree(webDavTransport, webDavLocator),
                    ),
                )
                LibraryViewModel(
                    library = ProgressCleanupMediaLibrary(
                        DirectoryMediaLibrary(tree),
                        RoomPlaybackProgressRepository.create(appContext),
                    ),
                    files = tree,
                    settings = SettingsRepository.create(appContext),
                    sources = sources,
                    verifier = RoutingNetworkSourceVerifier(
                        mapOf(
                            SmbLocation.SCHEME to SmbSourceVerifier(smbConnector),
                            WebDavAddress.SCHEME_HTTP to WebDavSourceVerifier(webDavTransport),
                            WebDavAddress.SCHEME_HTTPS to WebDavSourceVerifier(webDavTransport),
                        ),
                    ),
                    positions = DataStoreDirectoryPositionStore.create(appContext),
                    navigation = navigation,
                    snapshots = DataStoreNavigationSnapshotStore.create(appContext),
                    diagnostics = diagnostics,
                )
            }
        }
    }
}
