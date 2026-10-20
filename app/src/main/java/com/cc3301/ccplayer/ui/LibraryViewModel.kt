package com.cc3301.ccplayer.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

    /** 套用展示偏好；排序变化回到顶部，其余变化按位置记录重新定位。 */
    fun withDisplayPreferences(
        viewMode: ViewMode,
        gridColumns: Int,
        sortOrder: SortOrder,
        autoRestorePosition: Boolean,
        sortChangeScrollsToTop: Boolean = true,
    ): LibraryState {
        val changed = viewMode != this.viewMode ||
            gridColumns != this.gridColumns ||
            sortOrder != this.sortOrder ||
            autoRestorePosition != this.autoRestorePosition
        val sortChanged = sortChangeScrollsToTop && sortOrder != this.sortOrder
        val updated = copy(
            viewMode = viewMode,
            gridColumns = gridColumns,
            sortOrder = sortOrder,
            autoRestorePosition = autoRestorePosition,
        )
        return when {
            !changed -> updated
            sortChanged -> updated.scrolledToTop(pendingWhileLoading = updated.loading)
            else -> updated.retargeted()
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
    private fun retargeted(): LibraryState = copy(
        scrollTarget = directoryScrollTarget(visibleEntries, savedPosition, autoRestorePosition),
        scrollRevision = scrollRevision + 1,
    )

    /** 回到顶部：按正常滚动规则把第一个可见条目记为当前位置。 */
    fun scrolledToTop(pendingWhileLoading: Boolean = false): LibraryState = copy(
        scrollTarget = if (visibleEntries.isEmpty()) null else DirectoryScrollTarget(index = 0, offset = 0),
        scrollRevision = scrollRevision + 1,
        pendingScrollToTop = pendingWhileLoading,
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
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _sourcesState = MutableStateFlow(SourcesState())
    val sourcesState: StateFlow<SourcesState> = _sourcesState.asStateFlow()

    private var loadJob: Job? = null

    /** 首次偏好读取只套用取值，不把排序差异当成用户切换。 */
    private var displayPreferencesApplied = false

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
                    current.withDisplayPreferences(
                        viewMode = preferences.viewMode,
                        gridColumns = preferences.gridColumns,
                        sortOrder = preferences.sortOrder,
                        autoRestorePosition = preferences.autoRestorePosition,
                        sortChangeScrollsToTop = !firstApply,
                    )
                }
            }
        }
        restoreDirectoryStack()
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
        _state.update {
            it.copy(
                sourceLocation = source.location,
                stack = listOf(source.root),
                entries = emptyList(),
                loading = true,
                failure = null,
                savedPosition = null,
                scrollTarget = null,
                pendingScrollToTop = false,
            )
        }
        persistDirectoryStack()
        load(source.root)
    }

    fun enter(directory: MediaFile) {
        _state.update { it.copy(stack = it.stack + directory, entries = emptyList()) }
        persistDirectoryStack()
        load(directory)
    }

    /** 回到上一级；停在连接源根目录时不动，由界面离开库页面。 */
    fun navigateUp() {
        val stack = _state.value.stack
        if (stack.size <= 1) return
        val parent = stack.dropLast(1).last()
        _state.update { it.copy(stack = stack.dropLast(1), entries = emptyList()) }
        persistDirectoryStack()
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

    /** 记录某个目录的第一个可见条目与滚动偏移；关闭位置记忆或加载中不写。 */
    fun savePosition(sourceLocation: String, relativePath: String, entryLocation: String, scrollOffset: Int) {
        if (sourceLocation.isBlank()) return
        val current = _state.value
        if (!current.autoRestorePosition || current.loading) return
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

    private fun load(directory: MediaFile) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, failure = null) }
        loadJob = viewModelScope.launch {
            val sourceLocation = _state.value.sourceLocation
            val relativePath = relativeDirectoryPath(_state.value.stack)
            val position = loadPosition(sourceLocation, relativePath)
            val result = library.list(directory)
            currentCoroutineContext().ensureActive()
            _state.update {
                it.withLoadedEntries(result.getOrDefault(emptyList()), result.exceptionOrNull().toFailure(), position)
            }
        }
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

    /** 把当前连接源与目录栈写进可恢复状态，进程重建后据此重新读取当前目录。 */
    private fun persistDirectoryStack() {
        val current = _state.value
        savedState[KEY_SOURCE_LOCATION] = current.sourceLocation
        savedState[KEY_STACK_LOCATIONS] = ArrayList(current.stack.map { it.location })
        savedState[KEY_STACK_NAMES] = ArrayList(current.stack.map { it.name })
    }

    /** 进程重建后恢复连接源与目录栈，再按位置记录重新读取当前目录。 */
    private fun restoreDirectoryStack() {
        val sourceLocation = savedState.get<String>(KEY_SOURCE_LOCATION) ?: return
        val locations = savedState.get<ArrayList<String>>(KEY_STACK_LOCATIONS) ?: return
        val names = savedState.get<ArrayList<String>>(KEY_STACK_NAMES) ?: return
        if (sourceLocation.isBlank() || locations.isEmpty() || locations.size != names.size) return
        val stack = locations.indices.map { index ->
            MediaFile(name = names[index], location = locations[index], isDirectory = true)
        }
        _state.update {
            it.copy(
                sourceLocation = sourceLocation,
                stack = stack,
                entries = emptyList(),
                loading = true,
                failure = null,
                savedPosition = null,
                scrollTarget = null,
                pendingScrollToTop = false,
            )
        }
        load(stack.last())
    }

    /** 影响列表展示与滚动的设置项。 */
    private data class ListPreferences(
        val viewMode: ViewMode,
        val gridColumns: Int,
        val sortOrder: SortOrder,
        val autoRestorePosition: Boolean,
    )

    companion object {
        private const val KEY_SOURCE_LOCATION = "library_source_location"
        private const val KEY_STACK_LOCATIONS = "library_stack_locations"
        private const val KEY_STACK_NAMES = "library_stack_names"

        /** 生产入口：本地 SAF 与各网络协议的文件树（列举目录后清已消失媒体的进度记录）+ DataStore 设置与书签。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
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
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}
